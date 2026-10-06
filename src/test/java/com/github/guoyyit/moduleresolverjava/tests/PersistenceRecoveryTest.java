package com.github.guoyyit.moduleresolverjava.tests;

import com.github.guoyyit.moduleresolverjava.engine.ResolutionEngine;
import com.github.guoyyit.moduleresolverjava.model.ResolutionSnapshot;
import com.github.guoyyit.moduleresolverjava.parser.ManifestParser;
import com.github.guoyyit.moduleresolverjava.service.ResolutionService;
import com.github.guoyyit.moduleresolverjava.state.EventJournal;
import com.github.guoyyit.moduleresolverjava.state.SnapshotStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersistenceRecoveryTest {

    private ResolutionService newService(Path stateDir) {
        ResolutionService service = new ResolutionService(new ManifestParser(),
                new ResolutionEngine(),
                new SnapshotStore(stateDir),
                new EventJournal(stateDir));
        service.start();
        return service;
    }

    @Test
    void recoversLatestCommittedSnapshotAfterRestart(@TempDir Path stateDir) {
        ResolutionService first = newService(stateDir);
        first.upsertFile("a.module", "module com.a\nrequires com.b\n");
        first.upsertFile("b.module", "module com.b\n");
        long version = first.currentVersion();
        first.stop();

        ResolutionService second = newService(stateDir);
        ResolutionSnapshot snapshot = second.current();

        assertEquals(version, snapshot.version());
        assertTrue(snapshot.declarations().containsKey("com.a"));
        assertTrue(snapshot.declarations().containsKey("com.b"));
        assertEquals(List.of("com.b"), snapshot.modules().get("com.a").directDependencies());
        second.stop();
    }

    @Test
    void journalReplaysUpdatesAfterLastCheckpoint(@TempDir Path stateDir) {
        ResolutionService first = newService(stateDir);
        first.upsertFile("a.module", "module com.a\n");
        first.stop();

        EventJournal journal = new EventJournal(stateDir);
        journal.append(new EventJournal.UpsertEvent(System.currentTimeMillis(),
                "b.module", "module com.b\n"));

        ResolutionService second = newService(stateDir);
        assertTrue(second.current().declarations().containsKey("com.a"));
        assertTrue(second.current().declarations().containsKey("com.b"));
        second.stop();
    }

    @Test
    void noHalfWrittenStateSurvivesCrash(@TempDir Path stateDir) throws Exception {
        ResolutionService service = newService(stateDir);
        service.upsertFile("a.module", "module com.a\n");
        service.stop();

        Files.createFile(stateDir.resolve("snapshot-1234.tmp"));
        Files.createFile(stateDir.resolve("journal-5678.tmp"));

        ResolutionService restarted = newService(stateDir);
        assertTrue(restarted.current().declarations().containsKey("com.a"));
        try (var paths = Files.list(stateDir)) {
            assertTrue(paths.noneMatch(path -> path.getFileName().toString().endsWith(".tmp")));
        }
        restarted.stop();
    }

    @Test
    void deleteEventIsRecovered(@TempDir Path stateDir) {
        ResolutionService first = newService(stateDir);
        first.upsertFile("a.module", "module com.a\n");
        first.deleteFile("a.module");
        first.stop();

        ResolutionService second = newService(stateDir);
        assertTrue(second.current().files().isEmpty());
        second.stop();
    }

    @Test
    void rebuildReplacesEverythingButKeepsVersionMonotonic(@TempDir Path stateDir) {
        ResolutionService service = newService(stateDir);
        service.upsertFile("a.module", "module com.a\n");
        long before = service.currentVersion();

        java.util.Map<String, String> replacement = new java.util.LinkedHashMap<>();
        replacement.put("x.module", "module com.x\nrequires com.y\n");
        replacement.put("y.module", "module com.y\n");
        ResolutionSnapshot rebuilt = service.rebuild(replacement);

        assertEquals(before + 1, rebuilt.version());
        assertTrue(rebuilt.declarations().containsKey("com.x"));
        assertFalse(rebuilt.declarations().containsKey("com.a"));
        service.stop();

        ResolutionService restarted = newService(stateDir);
        assertEquals(before + 1, restarted.currentVersion());
        assertTrue(restarted.current().declarations().containsKey("com.x"));
        assertFalse(restarted.current().declarations().containsKey("com.a"));
        restarted.stop();
    }

    /**
     * Visibility conclusions survive the persistence round-trip, and
     * incremental resolution continued after a restart lands on exactly the
     * same conclusions as an uninterrupted service applying the same edits.
     */
    @Test
    void recoveryPreservesVisibilityAndContinuesIncrementally(@TempDir Path crashedDir,
                                                              @TempDir Path referenceDir) {
        String[][] initialOps = {
                {"app.module", "module com.app\nrequires com.biz\nconsumes api.Greeter\n"},
                {"biz.module", "module com.biz\nrequires transitive com.core\n"},
                {"core.module",
                        "module com.core\nexports api\nprovides api.Greeter with impl.GreeterImpl\n"},
        };
        String[][] laterOps = {
                // Shrink visibility: drop the propagation modifier.
                {"biz.module", "module com.biz\nrequires com.core\n"},
                // Add a new consumer of the (now unbound) service.
                {"other.module", "module com.other\nrequires com.biz\nconsumes api.Greeter\n"},
                // Restore propagation.
                {"biz.module", "module com.biz\nrequires transitive com.core\n"},
        };

        ResolutionService crashed = newService(crashedDir);
        for (String[] op : initialOps) {
            crashed.upsertFile(op[0], op[1]);
        }
        // Visibility data is part of the committed snapshot.
        assertEquals(List.of("com.biz", "com.core"),
                crashed.current().modules().get("com.app").readableModules());
        crashed.stop();

        // Restart: the recovered snapshot must carry the same visibility
        // conclusions, then keep accepting incremental updates.
        ResolutionService recovered = newService(crashedDir);
        assertEquals(List.of("com.biz", "com.core"),
                recovered.current().modules().get("com.app").readableModules());
        assertEquals("BOUND",
                recovered.current().serviceBindings().get("com.app").get(0).status());

        ResolutionService reference = newService(referenceDir);
        for (String[] op : initialOps) {
            reference.upsertFile(op[0], op[1]);
        }
        for (String[] op : laterOps) {
            recovered.upsertFile(op[0], op[1]);
            reference.upsertFile(op[0], op[1]);
        }

        assertEquals(reference.current().modules(), recovered.current().modules());
        assertEquals(reference.current().serviceBindings(),
                recovered.current().serviceBindings());
        assertEquals(reference.current().packageOwners(),
                recovered.current().packageOwners());
        assertEquals(reference.currentVersion(), recovered.currentVersion());
        // Sanity: the final state really uses propagation.
        assertEquals(List.of("com.biz", "com.core"),
                recovered.current().modules().get("com.app").readableModules());
        assertEquals(List.of("com.biz", "com.core"),
                recovered.current().modules().get("com.other").readableModules());
        recovered.stop();
        reference.stop();
    }

    /**
     * A crash between the journal append and the snapshot write leaves the
     * previous committed snapshot plus a replayable journal event; recovery
     * must reach the same conclusions as a clean commit.
     */
    @Test
    void crashBetweenJournalAndSnapshotReplaysVisibilityEvent(@TempDir Path stateDir) {
        ResolutionService first = newService(stateDir);
        first.upsertFile("app.module", "module com.app\nrequires com.biz\n");
        first.upsertFile("biz.module", "module com.biz\nrequires transitive com.core\n");
        first.stop();

        // Simulate a crash right after journaling an upsert but before the
        // snapshot/checkpoint of that write completed.
        EventJournal journal = new EventJournal(stateDir);
        journal.append(new EventJournal.UpsertEvent(System.currentTimeMillis(),
                "core.module", "module com.core\nexports pkg.core\n"));

        ResolutionService recovered = newService(stateDir);
        assertEquals(List.of("com.biz", "com.core"),
                recovered.current().modules().get("com.app").readableModules());
        assertTrue(recovered.current().declarations().containsKey("com.core"));
        recovered.stop();
    }

    /**
     * Snapshots persisted before the propagation feature existed carry no
     * {@code transitive} flag on requires edges and no {@code readableModules}
     * list on module conclusions. They must still load: requires edges then
     * behave as plain non-propagating dependencies and readability is
     * recomputed on the next resolution.
     */
    @Test
    void snapshotFromPreviousVersionStillLoads(@TempDir Path stateDir) throws Exception {
        String legacyJson = """
                {
                  "version": 7,
                  "committedAt": "2026-01-01T00:00:00Z",
                  "files": {
                    "app.module": {
                      "file": "app.module", "contentHash": "ab", "success": true,
                      "declaration": {
                        "name": "com.app", "file": "app.module", "contentHash": "ab",
                        "requires": [{"name": "com.core", "optional": false,
                                      "position": {"file": "app.module", "line": 2, "column": 10}}],
                        "exports": [], "provides": [], "consumes": []
                      },
                      "diagnostics": []
                    }
                  },
                  "declarations": {
                    "com.app": {
                      "name": "com.app", "file": "app.module", "contentHash": "ab",
                      "requires": [{"name": "com.core", "optional": false,
                                    "position": {"file": "app.module", "line": 2, "column": 10}}],
                      "exports": [], "provides": [], "consumes": []
                    }
                  },
                  "modules": {
                    "com.app": {
                      "moduleName": "com.app", "file": "app.module",
                      "directDependencies": ["com.core"],
                      "transitiveDependencies": [],
                      "onCycle": false, "cycles": [], "diagnostics": []
                    }
                  },
                  "globalDiagnostics": [],
                  "packageOwners": {},
                  "serviceBindings": {}
                }
                """;
        Files.writeString(stateDir.resolve("snapshot.json"), legacyJson);

        ResolutionService service = newService(stateDir);
        ResolutionSnapshot snapshot = service.current();
        assertEquals(7, snapshot.version());
        assertFalse(snapshot.declarations().get("com.app").requires().get(0).transitive());
        assertEquals(List.of(),
                snapshot.modules().get("com.app").readableModules());

        // Incremental resolution continues normally on the recovered state.
        ResolutionSnapshot next = service.upsertFile("core.module",
                "module com.core\nexports pkg.core\n");
        assertEquals(8, next.version());
        assertEquals(List.of("com.core"),
                next.modules().get("com.app").readableModules());
        service.stop();
    }
}

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

    @Test
    void recoveryThenIncrementalMatchesNoCrashRun(@TempDir Path stateDir,
                                                  @TempDir Path referenceDir) {
        ResolutionService first = newService(stateDir);
        first.upsertFile("app.module",
                "module com.app\nrequires com.mid\nconsumes api.Greeter\n");
        first.upsertFile("mid.module", "module com.mid\nrequires transitive com.core\n");
        first.upsertFile("core.module",
                "module com.core\nexports api\nprovides api.Greeter with impl.Greeter\n");
        long committedVersion = first.currentVersion();
        first.stop();

        // Simulate a crash after the journal append but before the snapshot
        // write: the event is durable and must be replayed exactly once.
        EventJournal journal = new EventJournal(stateDir);
        journal.append(new EventJournal.UpsertEvent(System.currentTimeMillis(),
                "mid.module", "module com.mid\nrequires com.core\n"));

        ResolutionService recovered = newService(stateDir);
        // The replayed write hid the provider: visibility and binding reflect
        // the journaled plain requires, not the stale snapshot.
        assertEquals(committedVersion + 1, recovered.currentVersion());
        assertEquals(List.of("com.mid"),
                recovered.current().modules().get("com.app").readableModules());
        assertEquals("MISSING_REQUIRED",
                recovered.current().serviceBindings().get("com.app").get(0).status());

        // Continue with incremental updates after the recovery: the provider
        // becomes visible again through the restored transitive edge.
        ResolutionSnapshot afterFlip = recovered.upsertFile("mid.module",
                "module com.mid\nrequires transitive com.core\n");
        assertEquals(List.of("com.core", "com.mid"),
                afterFlip.modules().get("com.app").readableModules());
        assertEquals("BOUND",
                afterFlip.serviceBindings().get("com.app").get(0).status());
        assertEquals("com.core",
                afterFlip.serviceBindings().get("com.app").get(0).selectedProvider());
        recovered.stop();

        // A service that never crashed must reach the identical conclusions
        // and the identical version for the same logical operations.
        ResolutionService reference = newService(referenceDir);
        reference.upsertFile("app.module",
                "module com.app\nrequires com.mid\nconsumes api.Greeter\n");
        reference.upsertFile("mid.module", "module com.mid\nrequires transitive com.core\n");
        reference.upsertFile("core.module",
                "module com.core\nexports api\nprovides api.Greeter with impl.Greeter\n");
        reference.upsertFile("mid.module", "module com.mid\nrequires com.core\n");
        ResolutionSnapshot referenceFinal = reference.upsertFile("mid.module",
                "module com.mid\nrequires transitive com.core\n");
        reference.stop();

        assertEquals(referenceFinal.version(), afterFlip.version());
        assertEquals(referenceFinal.modules(), afterFlip.modules());
        assertEquals(referenceFinal.serviceBindings(), afterFlip.serviceBindings());
        assertEquals(referenceFinal.packageOwners(), afterFlip.packageOwners());
        assertEquals(referenceFinal.globalDiagnostics(), afterFlip.globalDiagnostics());
    }
}

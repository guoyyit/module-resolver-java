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
}

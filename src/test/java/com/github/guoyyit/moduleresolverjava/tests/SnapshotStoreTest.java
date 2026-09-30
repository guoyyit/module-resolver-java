package com.github.guoyyit.moduleresolverjava.tests;

import com.github.guoyyit.moduleresolverjava.resolve.ResolutionSnapshot;
import com.github.guoyyit.moduleresolverjava.system.IncrementalModuleSystem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SnapshotStoreTest {

    private static String declaration(String name) {
        return "module " + name + " {\n}\n";
    }

    @Test
    void halfWrittenSnapshotIsIgnoredAndPreviousStateRecovered(@TempDir Path stateDir)
            throws Exception {
        IncrementalModuleSystem system = new IncrementalModuleSystem(stateDir);
        system.upsertFile("/a.mod", declaration("com.a"));
        system.upsertFile("/b.mod", declaration("com.b"));

        // Simulate a crash mid-commit: corrupt temp file + dangling pointer.
        Path temp = stateDir.resolve("snapshot-v3.json.tmp-99999");
        Files.writeString(temp, "{ \"version\": 3, \"modules\": { TRUNCATED",
                StandardCharsets.UTF_8);
        Files.writeString(stateDir.resolve("CURRENT.tmp-99999"),
                "snapshot-v3.json\n", StandardCharsets.UTF_8);

        IncrementalModuleSystem restarted = new IncrementalModuleSystem(stateDir);
        ResolutionSnapshot recovered = restarted.recover();
        assertEquals(2, recovered.version());
        assertTrue(recovered.modules().containsKey("com.a"));
        assertTrue(recovered.modules().containsKey("com.b"));

        // Next commit cleans temp files and keeps working incrementally
        ResolutionSnapshot next = restarted.upsertFile("/c.mod",
                declaration("com.c"));
        assertEquals(3, next.version());
        assertTrue(next.modules().containsKey("com.c"));
        try (var stream = Files.list(stateDir)) {
            assertTrue(stream.noneMatch(path ->
                    path.getFileName().toString().contains(".tmp-99999")));
        }
    }

    @Test
    void snapshotHistoryIsBounded(@TempDir Path stateDir) {
        IncrementalModuleSystem system = new IncrementalModuleSystem(stateDir);
        for (int i = 0; i < 80; i++) {
            system.upsertFile("/m-" + i + ".mod", declaration("com.m" + i));
        }
        assertEquals(50, system.current().history().size());
        assertEquals(80, system.current().version());
    }
}

package com.github.guoyyit.moduleresolverjava.tests;

import com.github.guoyyit.moduleresolverjava.resolve.ResolutionSnapshot;
import com.github.guoyyit.moduleresolverjava.system.IncrementalModuleSystem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyncDirectoryTest {

    @Test
    void syncsAddsAndRemovesFilesAndStaysIncremental(@TempDir Path stateDir,
                                                     @TempDir Path modulesDir)
            throws Exception {
        IncrementalModuleSystem system = new IncrementalModuleSystem(stateDir);
        Files.writeString(modulesDir.resolve("a.mod"),
                "module com.a {\nrequires com.b;\n}\n", StandardCharsets.UTF_8);
        Files.writeString(modulesDir.resolve("b.mod"),
                "module com.b {\n}\n", StandardCharsets.UTF_8);

        ResolutionSnapshot first = system.syncDirectory(modulesDir, null);
        assertEquals(2, first.modules().size());
        assertFalse(first.hasErrors());

        ResolutionSnapshot unchanged = system.syncDirectory(modulesDir, null);
        // No file changed -> no new commit
        assertEquals(first.version(), unchanged.version());

        Files.writeString(modulesDir.resolve("c.mod"),
                "module com.c {\n}\n", StandardCharsets.UTF_8);
        Files.delete(modulesDir.resolve("b.mod"));
        ResolutionSnapshot changed = system.syncDirectory(modulesDir, null);

        assertEquals(first.version() + 1, changed.version());
        assertTrue(changed.modules().containsKey("com.c"));
        assertFalse(changed.modules().containsKey("com.b"));
        // com.a now requires a missing module -> error reported
        assertTrue(changed.hasErrors());
        assertTrue(changed.diagnostics().stream()
                .anyMatch(diagnostic -> diagnostic.code().equals("REQUIRES_MISSING")));
    }
}

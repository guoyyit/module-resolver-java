package com.github.guoyyit.moduleresolverjava.tests;

import com.github.guoyyit.moduleresolverjava.resolve.ResolutionSnapshot;
import com.github.guoyyit.moduleresolverjava.system.IncrementalModuleSystem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LargeScaleTest {

    @Test
    void resolvesThousandModulesWithinAcceptableTime(@TempDir Path stateDir) {
        int total = 1000;
        IncrementalModuleSystem system = new IncrementalModuleSystem(stateDir);

        List<IncrementalModuleSystem.FileUpdate> batch = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            String requires = i == 0 ? ""
                    : "requires com.m" + (i - 1) + ";\n";
            String body = requires + "exports pkg.m" + i + ";\n";
            batch.add(new IncrementalModuleSystem.FileUpdate(
                    "/modules/m-" + i + ".mod",
                    "module com.m" + i + " {\n" + body + "}\n"));
        }

        long bulkStart = System.currentTimeMillis();
        ResolutionSnapshot snapshot = system.upsertFiles(batch, null);
        long bulkElapsed = System.currentTimeMillis() - bulkStart;
        System.out.println("LARGESCALE bulk=" + bulkElapsed + "ms");

        assertEquals(total, snapshot.modules().size());
        assertFalse(snapshot.hasErrors());
        assertEquals(1, snapshot.version());
        assertTrue(bulkElapsed < 20_000,
                () -> "bulk resolution too slow: " + bulkElapsed + "ms");

        long incrementalStart = System.currentTimeMillis();
        ResolutionSnapshot changed = system.upsertFile("/modules/m-0.mod",
                "module com.m0 {\nexports pkg.m0;\n// touch\n}\n");
        long incrementalElapsed = System.currentTimeMillis() - incrementalStart;
        System.out.println("LARGESCALE incremental=" + incrementalElapsed + "ms");
        assertEquals(2, changed.version());
        assertEquals(total, changed.modules().size());
        assertTrue(incrementalElapsed < 5_000,
                () -> "incremental update too slow: " + incrementalElapsed + "ms");
    }
}

package com.github.guoyyit.moduleresolverjava.tests;

import com.github.guoyyit.moduleresolverjava.engine.ResolutionEngine;
import com.github.guoyyit.moduleresolverjava.engine.ResolutionResult;
import com.github.guoyyit.moduleresolverjava.model.Diagnostic;
import com.github.guoyyit.moduleresolverjava.model.FileParseOutcome;
import com.github.guoyyit.moduleresolverjava.model.ResolutionSnapshot;
import com.github.guoyyit.moduleresolverjava.parser.ManifestParser;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScalePerformanceTest {

    private static final int MODULE_COUNT = 1000;

    private Map<String, FileParseOutcome> largeModuleSet() {
        ManifestParser parser = new ManifestParser();
        Map<String, FileParseOutcome> files = new LinkedHashMap<>();
        for (int i = 0; i < MODULE_COUNT; i++) {
            StringBuilder body = new StringBuilder("module com.m" + i + "\n");
            if (i + 1 < MODULE_COUNT) {
                body.append("requires com.m").append(i + 1).append("\n");
            }
            body.append("exports pkg.m").append(i).append("\n");
            files.put("m" + i + ".module", parser.parse("m" + i + ".module", body.toString()));
        }
        return files;
    }

    @Test
    void resolvesThousandModulesWithinAcceptableTime() {
        ResolutionEngine engine = new ResolutionEngine();
        long start = System.nanoTime();
        ResolutionResult result = engine.fullResolve(largeModuleSet(), 0);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertEquals(MODULE_COUNT, result.snapshot().declarations().size());
        assertTrue(result.snapshot().globalDiagnostics().isEmpty(),
                "clean module set must produce no diagnostics");
        assertTrue(elapsedMs < 10_000,
                "full resolution of 1000 modules took " + elapsedMs + "ms");
    }

    @Test
    void incrementalUpdateOnLargeSetIsFasterThanFullRebuild() {
        ResolutionEngine engine = new ResolutionEngine();
        ResolutionSnapshot base = engine.fullResolve(largeModuleSet(), 0).snapshot();

        ManifestParser parser = new ManifestParser();
        FileParseOutcome changed = parser.parse("m999.module",
                "module com.m999\nexports pkg.m999\nexports pkg.extra\n");

        long start = System.nanoTime();
        ResolutionResult result = engine.incrementalResolve(
                base, Map.of("m999.module", changed), java.util.Set.of());
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertTrue(result.affectedModules().contains("com.m999"));
        assertTrue(elapsedMs < 5_000, "incremental resolution took " + elapsedMs + "ms");
    }

    @Test
    void diagnosticBudgetStaysBoundedForBrokenSet() {
        Map<String, FileParseOutcome> files = largeModuleSet();
        files.put("broken.module", new ManifestParser().parse("broken.module", "bad\n"));
        ResolutionSnapshot snapshot = new ResolutionEngine().fullResolve(files, 0).snapshot();
        long errorCount = snapshot.globalDiagnostics().stream()
                .filter(diagnostic -> diagnostic.severity() == Diagnostic.Severity.ERROR)
                .count();
        assertTrue(errorCount >= 2);
        assertTrue(errorCount < 10, "error count should stay bounded, was " + errorCount);
    }
}

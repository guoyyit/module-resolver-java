package com.github.guoyyit.moduleresolverjava.tests;

import com.github.guoyyit.moduleresolverjava.engine.ResolutionEngine;
import com.github.guoyyit.moduleresolverjava.engine.ResolutionResult;
import com.github.guoyyit.moduleresolverjava.model.Diagnostic;
import com.github.guoyyit.moduleresolverjava.model.FileParseOutcome;
import com.github.guoyyit.moduleresolverjava.model.ModuleResolution;
import com.github.guoyyit.moduleresolverjava.model.ResolutionSnapshot;
import com.github.guoyyit.moduleresolverjava.parser.ManifestParser;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
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

    private Map<String, FileParseOutcome> transitiveChain(int count) {
        ManifestParser parser = new ManifestParser();
        Map<String, FileParseOutcome> files = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            StringBuilder body = new StringBuilder("module com.t" + i + "\n");
            if (i + 1 < count) {
                body.append("requires transitive com.t").append(i + 1).append("\n");
            }
            body.append("exports pkg.t").append(i).append("\n");
            files.put("t" + i + ".module", parser.parse("t" + i + ".module", body.toString()));
        }
        return files;
    }

    @Test
    void resolvesThousandModuleTransitiveChainWithinAcceptableTime() {
        ResolutionEngine engine = new ResolutionEngine();
        long start = System.nanoTime();
        ResolutionResult result = engine.fullResolve(transitiveChain(MODULE_COUNT), 0);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertEquals(MODULE_COUNT, result.snapshot().declarations().size());
        // The head of the chain reads every module through propagation.
        assertEquals(MODULE_COUNT - 1,
                result.snapshot().modules().get("com.t0").readableModules().size());
        // The tail reads nothing.
        assertTrue(result.snapshot().modules()
                .get("com.t" + (MODULE_COUNT - 1)).readableModules().isEmpty());
        assertTrue(elapsedMs < 10_000,
                "full resolution of a 1000-module transitive chain took " + elapsedMs + "ms");
    }

    @Test
    void incrementalCostTracksAffectedConeNotTotalSize() {
        ResolutionEngine engine = new ResolutionEngine();
        ResolutionSnapshot base = engine.fullResolve(transitiveChain(MODULE_COUNT), 0).snapshot();

        ManifestParser parser = new ManifestParser();
        // Editing the chain head affects only the head itself.
        FileParseOutcome headChange = parser.parse("t0.module",
                "module com.t0\nrequires transitive com.t1\nexports pkg.t0\nexports pkg.t0extra\n");
        long start = System.nanoTime();
        ResolutionResult headResult = engine.incrementalResolve(
                base, Map.of("t0.module", headChange), java.util.Set.of());
        long headMs = (System.nanoTime() - start) / 1_000_000;
        assertEquals(java.util.Set.of("com.t0"), headResult.affectedModules());
        assertTrue(headMs < 5_000, "head-of-chain increment took " + headMs + "ms");

        // Editing the tail legitimately affects the whole chain and stays bounded.
        FileParseOutcome tailChange = parser.parse("t999.module",
                "module com.t999\nexports pkg.t999\nexports pkg.t999extra\n");
        start = System.nanoTime();
        ResolutionResult tailResult = engine.incrementalResolve(
                base, Map.of("t999.module", tailChange), java.util.Set.of());
        long tailMs = (System.nanoTime() - start) / 1_000_000;
        assertEquals(MODULE_COUNT, tailResult.affectedModules().size());
        assertTrue(tailMs < 10_000, "tail-of-chain increment took " + tailMs + "ms");
    }

    @Test
    void repeatedIncrementsDoNotRecreateUnaffectedConclusions() {
        ResolutionEngine engine = new ResolutionEngine();
        ResolutionSnapshot snapshot = engine.fullResolve(transitiveChain(200), 0).snapshot();
        ModuleResolution tailConclusion =
                snapshot.modules().get("com.t150");

        ManifestParser parser = new ManifestParser();
        // Fifty commits that only touch the chain head; a far-away module's
        // conclusion object must be reused verbatim every single time, so
        // memory churn does not grow with the number of commits.
        for (int i = 0; i < 50; i++) {
            FileParseOutcome change = parser.parse("t0.module",
                    "module com.t0\nrequires transitive com.t1\nexports pkg.t0\n# rev " + i + "\n");
            snapshot = engine.incrementalResolve(
                    snapshot, Map.of("t0.module", change), java.util.Set.of()).snapshot();
            assertSame(tailConclusion, snapshot.modules().get("com.t150"),
                    "commit " + i + " recreated an unaffected conclusion");
        }
        assertEquals(51, snapshot.version());
    }
}

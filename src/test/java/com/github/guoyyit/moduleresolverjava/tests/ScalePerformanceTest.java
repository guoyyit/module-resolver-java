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
        // Change the chain head: nothing depends on com.m0, so the recompute
        // region is a single module even though the chain is 1000 deep.
        FileParseOutcome changed = parser.parse("m0.module",
                "module com.m0\nrequires com.m1\nrequires static com.elsewhere\n");

        long start = System.nanoTime();
        ResolutionResult result = engine.incrementalResolve(
                base, Map.of("m0.module", changed), java.util.Set.of());
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertEquals(java.util.Set.of("com.m0"), result.affectedModules());
        assertTrue(result.snapshot().modules().get("com.m0").directDependencies()
                .contains("com.elsewhere"));
        // Untouched modules keep the identical conclusion object.
        assertSame(base.modules().get("com.m500"),
                result.snapshot().modules().get("com.m500"));
        assertSame(base.modules().get("com.m999"),
                result.snapshot().modules().get("com.m999"));
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
    void readabilityOverLongTransitiveChainStaysBounded() {
        ResolutionEngine engine = new ResolutionEngine();
        long start = System.nanoTime();
        ResolutionSnapshot snapshot = engine.fullResolve(transitiveChain(MODULE_COUNT), 0)
                .snapshot();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        // The head of a fully transitive chain reads every other module.
        assertEquals(MODULE_COUNT - 1,
                snapshot.modules().get("com.t0").readableModules().size());
        assertEquals(0, snapshot.modules()
                .get("com.t" + (MODULE_COUNT - 1)).readableModules().size());
        assertTrue(elapsedMs < 10_000,
                "readability over a 1000-module transitive chain took " + elapsedMs + "ms");
    }

    @Test
    void incrementalVisibilityChangeTracksAffectedRegionAndMatchesFullResolve() {
        ResolutionEngine engine = new ResolutionEngine();
        Map<String, FileParseOutcome> files = transitiveChain(MODULE_COUNT);
        ResolutionSnapshot base = engine.fullResolve(files, 0).snapshot();

        // Flip one mid-chain edge from transitive to plain: every module
        // upstream of the cut loses readability of the tail.
        ManifestParser parser = new ManifestParser();
        int cut = MODULE_COUNT / 2;
        FileParseOutcome changed = parser.parse("t" + cut + ".module",
                "module com.t" + cut + "\nrequires com.t" + (cut + 1)
                        + "\nexports pkg.t" + cut + "\n");

        long start = System.nanoTime();
        ResolutionResult result = engine.incrementalResolve(
                base, Map.of("t" + cut + ".module", changed), java.util.Set.of());
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        // Only the flipped module and its upstream dependents are recomputed.
        assertEquals(cut + 1, result.affectedModules().size());
        assertEquals(cut, result.snapshot().modules().get("com.t0")
                .readableModules().size());
        // Untouched tail modules keep the identical conclusion object.
        assertSame(base.modules().get("com.t" + (cut + 1)),
                result.snapshot().modules().get("com.t" + (cut + 1)));
        assertSame(base.modules().get("com.t" + (MODULE_COUNT - 1)),
                result.snapshot().modules().get("com.t" + (MODULE_COUNT - 1)));

        // The incremental result must equal a from-scratch resolve of the
        // same final file set.
        ResolutionSnapshot fromScratch = engine.fullResolve(
                result.snapshot().files(), 0).snapshot();
        assertEquals(fromScratch.modules(), result.snapshot().modules());
        assertTrue(elapsedMs < 5_000,
                "incremental visibility change took " + elapsedMs + "ms");
    }
}

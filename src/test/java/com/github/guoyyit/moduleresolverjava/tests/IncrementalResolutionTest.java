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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IncrementalResolutionTest {

    private final ManifestParser parser = new ManifestParser();
    private final ResolutionEngine engine = new ResolutionEngine();

    private FileParseOutcome outcome(String module, String body) {
        return parser.parse(module + ".module", body);
    }

    private Map<String, FileParseOutcome> baseFiles() {
        Map<String, FileParseOutcome> files = new LinkedHashMap<>();
        files.put("app.module", outcome("app",
                "module com.app\nrequires com.biz\n"));
        files.put("biz.module", outcome("biz",
                "module com.biz\nrequires com.core\nexports pkg.biz\n"));
        files.put("core.module", outcome("core",
                "module com.core\nexports pkg.core\n"));
        return files;
    }

    @Test
    void unchangedModulesKeepStableConclusions() {
        ResolutionResult first = engine.fullResolve(baseFiles(), 0);
        ResolutionSnapshot snapshot1 = first.snapshot();

        Map<String, FileParseOutcome> changed = new LinkedHashMap<>();
        changed.put("core.module", outcome("core",
                "module com.core\nexports pkg.core\nexports pkg.extra\n"));
        ResolutionResult second = engine.incrementalResolve(snapshot1, changed, Set.of());

        // Unaffected modules must keep the very same conclusion object, not
        // just an equal copy.
        assertSame(snapshot1.modules().get("com.app"),
                second.snapshot().modules().get("com.app"));
        assertSame(snapshot1.modules().get("com.biz"),
                second.snapshot().modules().get("com.biz"));
        assertEquals(snapshot1.modules().get("com.app"),
                second.snapshot().modules().get("com.app"));
        assertEquals(2, second.snapshot().version());
    }

    @Test
    void onlyAffectedModulesAreRecomputed() {
        ResolutionSnapshot base = engine.fullResolve(baseFiles(), 0).snapshot();

        // A no-op upsert (identical content) changes no conclusion: the
        // affected set is empty and every module keeps the exact same
        // conclusion object.
        Map<String, FileParseOutcome> noOp = new LinkedHashMap<>();
        noOp.put("core.module", outcome("core",
                "module com.core\nexports pkg.core\n"));
        ResolutionResult result = engine.incrementalResolve(base, noOp, Set.of());

        assertTrue(result.affectedModules().isEmpty(),
                "no-op upsert must not recompute any module, got " + result.affectedModules());
        assertEquals(Set.of("core.module"), result.reparsedFiles().keySet());
        assertSame(base.modules().get("com.core"),
                result.snapshot().modules().get("com.core"));
        assertSame(base.modules().get("com.biz"),
                result.snapshot().modules().get("com.biz"));
        assertSame(base.modules().get("com.app"),
                result.snapshot().modules().get("com.app"));

        // An isolated new module affects only itself; every unrelated module
        // keeps the previous conclusion object.
        Map<String, FileParseOutcome> unrelatedChange = new LinkedHashMap<>();
        unrelatedChange.put("new.module", outcome("new",
                "module com.iso\n"));
        ResolutionResult isolated = engine.incrementalResolve(base, unrelatedChange, Set.of());
        assertEquals(Set.of("com.iso"), isolated.affectedModules());
        assertSame(base.modules().get("com.app"),
                isolated.snapshot().modules().get("com.app"));
        assertSame(base.modules().get("com.biz"),
                isolated.snapshot().modules().get("com.biz"));
    }

    @Test
    void parseFailureIsReportedWithPositionAndRecoversOnFix() {
        ResolutionSnapshot base = engine.fullResolve(baseFiles(), 0).snapshot();

        FileParseOutcome broken = parser.parse("core.module", "module com.core\nexport pkg.core\n");
        assertFalse(broken.success());
        ResolutionResult brokenResult =
                engine.incrementalResolve(base, Map.of("core.module", broken), Set.of());
        assertTrue(brokenResult.snapshot().globalDiagnostics().stream()
                .anyMatch(d -> "UNKNOWN_DIRECTIVE".equals(d.code())
                        && d.position().line() == 2));
        assertFalse(brokenResult.snapshot().declarations().containsKey("com.core"));

        FileParseOutcome fixed = outcome("core", "module com.core\nexports pkg.core\n");
        ResolutionResult recovered = engine.incrementalResolve(
                brokenResult.snapshot(), Map.of("core.module", fixed), Set.of());
        assertTrue(recovered.snapshot().declarations().containsKey("com.core"));
        assertTrue(recovered.snapshot().globalDiagnostics().stream()
                .noneMatch(d -> "UNKNOWN_DIRECTIVE".equals(d.code())));
    }

    @Test
    void resultIsIndependentOfOrder() {
        Map<String, FileParseOutcome> a = baseFiles();
        Map<String, FileParseOutcome> b = new LinkedHashMap<>();
        b.put(a.get("core.module").file(), a.get("core.module"));
        b.put(a.get("biz.module").file(), a.get("biz.module"));
        b.put(a.get("app.module").file(), a.get("app.module"));

        ResolutionSnapshot snapshotA = engine.fullResolve(a, 0).snapshot();
        ResolutionSnapshot snapshotB = engine.fullResolve(b, 0).snapshot();

        assertEquals(snapshotA.modules(), snapshotB.modules());
        assertEquals(snapshotA.globalDiagnostics(), snapshotB.globalDiagnostics());
        assertEquals(snapshotA.packageOwners(), snapshotB.packageOwners());
    }

    @Test
    void deletingFileIsReflectedIncrementally() {
        ResolutionSnapshot base = engine.fullResolve(baseFiles(), 0).snapshot();
        ResolutionResult afterDelete =
                engine.incrementalResolve(base, Map.of(), Set.of("core.module"));

        assertFalse(afterDelete.snapshot().declarations().containsKey("com.core"));
        assertTrue(afterDelete.snapshot().globalDiagnostics().stream()
                .anyMatch(d -> "REQUIRED_DEPENDENCY_MISSING".equals(d.code())));
        // The dangling edge is kept in com.biz's direct dependencies, so no
        // module-level conclusion changes; only the global diagnostic set
        // grows, hence no module is recomputed.
        assertTrue(afterDelete.affectedModules().isEmpty(),
                "dangling dependency keeps conclusions stable, got "
                        + afterDelete.affectedModules());
        assertSame(base.modules().get("com.biz"),
                afterDelete.snapshot().modules().get("com.biz"));
    }

    @Test
    void malformedInputDoesNotCancelOtherDiagnostics() {
        Map<String, FileParseOutcome> files = baseFiles();
        files.put("bad.module", parser.parse("bad.module", "bogus directive\n"));
        ResolutionResult result = engine.fullResolve(files, 0);
        assertTrue(result.snapshot().globalDiagnostics().stream()
                .anyMatch(d -> Diagnostic.Severity.ERROR == d.severity()
                        && "UNKNOWN_DIRECTIVE".equals(d.code())));
        assertTrue(result.snapshot().declarations().containsKey("com.app"));
    }

    @Test
    void ownershipChangeRipplesToExportersOfThePackage() {
        Map<String, FileParseOutcome> files = new LinkedHashMap<>();
        files.put("app.module", outcome("app",
                "module com.app\nrequires com.core\nexports pkg.shared\n"));
        files.put("core.module", outcome("core",
                "module com.core\nexports pkg.shared\n"));
        ResolutionSnapshot base = engine.fullResolve(files, 0).snapshot();
        // PARENT_WINS: com.app shadows the package and carries the warning.
        assertTrue(base.modules().get("com.app").diagnostics().stream()
                .anyMatch(d -> "SHADOWED_EXPORT".equals(d.code())));

        // An unrelated module starts exporting the same package: the rule
        // flips to AMBIGUOUS_EXPORT and com.app's conclusion changes even
        // though com.app does not depend on the new module.
        ResolutionResult widened = engine.incrementalResolve(base,
                Map.of("x.module", outcome("x",
                        "module com.x\nexports pkg.shared\n")),
                Set.of());
        assertTrue(widened.affectedModules().contains("com.app"),
                "ownership rule change must ripple to com.app, got "
                        + widened.affectedModules());
        assertTrue(widened.snapshot().modules().get("com.app").diagnostics().stream()
                .noneMatch(d -> "SHADOWED_EXPORT".equals(d.code())));
        assertTrue(widened.snapshot().globalDiagnostics().stream()
                .anyMatch(d -> "AMBIGUOUS_EXPORT".equals(d.code())));

        // Removing it again restores PARENT_WINS and the shadow warning.
        ResolutionResult restored = engine.incrementalResolve(widened.snapshot(),
                Map.of(), Set.of("x.module"));
        assertTrue(restored.affectedModules().contains("com.app"));
        assertTrue(restored.snapshot().modules().get("com.app").diagnostics().stream()
                .anyMatch(d -> "SHADOWED_EXPORT".equals(d.code())));
    }

    @Test
    void ownershipChangeRipplesToServiceBindingsThroughConsumedPackage() {
        Map<String, FileParseOutcome> files = new LinkedHashMap<>();
        files.put("consumer.module", outcome("consumer",
                "module com.consumer\nrequires com.provider\nconsumes api.Service\n"));
        files.put("provider.module", outcome("provider",
                "module com.provider\nexports api\nprovides api.Service with impl.ServiceImpl\n"));
        ResolutionSnapshot base = engine.fullResolve(files, 0).snapshot();
        assertEquals("BOUND", base.serviceBindings().get("com.consumer").get(0).status());

        // An unrelated module exports the interface package: the ownership
        // becomes ambiguous, so the provider's package is no longer usable
        // and the binding flips to MISSING even though the consumer's
        // dependencies did not change.
        ResolutionResult ambiguous = engine.incrementalResolve(base,
                Map.of("squatter.module", outcome("squatter",
                        "module com.squatter\nexports api\n")),
                Set.of());
        assertTrue(ambiguous.affectedModules().contains("com.consumer"),
                "binding change must mark the consumer affected, got "
                        + ambiguous.affectedModules());
        assertEquals("MISSING_REQUIRED",
                ambiguous.snapshot().serviceBindings().get("com.consumer").get(0).status());

        ResolutionResult restored = engine.incrementalResolve(ambiguous.snapshot(),
                Map.of(), Set.of("squatter.module"));
        assertEquals("BOUND",
                restored.snapshot().serviceBindings().get("com.consumer").get(0).status());
        assertTrue(restored.affectedModules().contains("com.consumer"));
    }
}

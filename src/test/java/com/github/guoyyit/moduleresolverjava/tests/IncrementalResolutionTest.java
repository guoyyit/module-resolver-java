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

        assertEquals(snapshot1.modules().get("com.app"),
                second.snapshot().modules().get("com.app"));
        assertEquals(snapshot1.modules().get("com.biz"),
                second.snapshot().modules().get("com.biz"));
        assertEquals(2, second.snapshot().version());
    }

    @Test
    void onlyAffectedModulesAreRecomputed() {
        ResolutionSnapshot base = engine.fullResolve(baseFiles(), 0).snapshot();

        Map<String, FileParseOutcome> changed = new LinkedHashMap<>();
        changed.put("core.module", outcome("core",
                "module com.core\nexports pkg.core\n"));
        ResolutionResult result = engine.incrementalResolve(base, changed, Set.of());

        assertTrue(result.affectedModules().contains("com.core"));
        assertTrue(result.affectedModules().contains("com.biz"));
        assertTrue(result.affectedModules().contains("com.app"));
        assertEquals(Set.of("core.module"), result.reparsedFiles().keySet());

        Map<String, FileParseOutcome> unrelatedChange = new LinkedHashMap<>();
        unrelatedChange.put("new.module", outcome("new",
                "module com.iso\n"));
        ResolutionResult isolated = engine.incrementalResolve(base, unrelatedChange, Set.of());
        assertEquals(Set.of("com.iso"), isolated.affectedModules());
        assertEquals(base.modules().get("com.app"),
                isolated.snapshot().modules().get("com.app"));
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
        assertTrue(afterDelete.affectedModules().contains("com.biz"));
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
}

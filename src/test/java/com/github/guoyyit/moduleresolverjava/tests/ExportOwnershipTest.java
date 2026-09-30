package com.github.guoyyit.moduleresolverjava.tests;

import com.github.guoyyit.moduleresolverjava.analysis.ExportOwnership;
import com.github.guoyyit.moduleresolverjava.graph.ModuleGraph;
import com.github.guoyyit.moduleresolverjava.model.Diagnostic;
import com.github.guoyyit.moduleresolverjava.model.ExportDirective;
import com.github.guoyyit.moduleresolverjava.model.ModuleDeclaration;
import com.github.guoyyit.moduleresolverjava.model.RequireDirective;
import com.github.guoyyit.moduleresolverjava.model.SourceLocation;
import com.github.guoyyit.moduleresolverjava.model.UseDirective;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExportOwnershipTest {

    private ModuleDeclaration exporter(String module, String exportedPackage,
                                       int line) {
        return TestModules.full(module, List.of(),
                List.of(new ExportDirective(exportedPackage,
                        new SourceLocation(module + ".mod", line, 5))),
                List.of(), List.of());
    }

    @Test
    void singleExporterBecomesOwnerWithoutDiagnostic() {
        ModuleGraph graph = new ModuleGraph(Map.of(
                "com.a", exporter("com.a", "pkg.api", 2)));
        ExportOwnership ownership = new ExportOwnership();

        assertEquals("com.a", ownership.resolveOwners(graph).get("pkg.api"));
        assertTrue(ownership.conflicts(graph).isEmpty());
    }

    @Test
    void multipleExportersProduceDeterministicOwnerAndConflicts() {
        ModuleGraph graph = new ModuleGraph(Map.of(
                "com.zeta", exporter("com.zeta", "pkg.api", 4),
                "com.alpha", exporter("com.alpha", "pkg.api", 9)));
        ExportOwnership ownership = new ExportOwnership();

        assertEquals(java.util.Set.of("com.alpha", "com.zeta"),
                ownership.exporters(graph).get("pkg.api"));
        List<Diagnostic> conflicts = ownership.conflicts(graph);
        assertEquals(1, conflicts.size());
        Diagnostic diagnostic = conflicts.get(0);
        assertEquals("EXPORT_CONFLICT", diagnostic.code());
        assertEquals(Diagnostic.Severity.ERROR, diagnostic.severity());
        assertEquals("com.zeta.mod", diagnostic.file());
        assertEquals(4, diagnostic.line());
        assertTrue(diagnostic.message().contains("deterministic owner is 'com.alpha'"));
        assertTrue(diagnostic.message().contains("com.zeta"));
    }

    @Test
    void conflictResultIsOrderIndependent() {
        ExportOwnership ownership = new ExportOwnership();
        ModuleGraph first = new ModuleGraph(Map.of(
                "com.zeta", exporter("com.zeta", "pkg.api", 4),
                "com.alpha", exporter("com.alpha", "pkg.api", 9)));
        ModuleGraph second = new ModuleGraph(Map.of(
                "com.alpha", exporter("com.alpha", "pkg.api", 9),
                "com.zeta", exporter("com.zeta", "pkg.api", 4)));

        assertEquals(ownership.conflicts(first), ownership.conflicts(second));
    }
}

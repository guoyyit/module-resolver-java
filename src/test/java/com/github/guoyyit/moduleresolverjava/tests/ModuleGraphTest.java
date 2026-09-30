package com.github.guoyyit.moduleresolverjava.tests;

import com.github.guoyyit.moduleresolverjava.analysis.CycleDetector;
import com.github.guoyyit.moduleresolverjava.graph.ModuleGraph;
import com.github.guoyyit.moduleresolverjava.model.Diagnostic;
import com.github.guoyyit.moduleresolverjava.model.ModuleDeclaration;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModuleGraphTest {

    private ModuleDeclaration module(String name, String... requires) {
        return TestModules.module(name, requires);
    }

    @Test
    void distinguishesDirectTransitiveAndReadableEdges() {
        ModuleDeclaration app = module("com.app", "com.core");
        ModuleDeclaration core = TestModules.transitiveModule("com.core",
                "com.api");
        ModuleDeclaration api = module("com.api");
        ModuleGraph graph = new ModuleGraph(
                Map.of("com.app", app, "com.core", core, "com.api", api));

        assertEquals(List.of("com.core"), graph.directDependencies("com.app"));
        assertEquals(java.util.Set.of("com.api"),
                graph.transitiveDependencies("com.app"));
        // api is readable because core `requires transitive` api
        assertTrue(graph.readableModules("com.app").contains("com.api"));
        assertTrue(graph.dependencyClosure("com.app")
                .containsAll(java.util.Set.of("com.core", "com.api")));
    }

    @Test
    void nonTransitiveRequiresDoesNotPropagateReadability() {
        ModuleDeclaration app = module("com.app", "com.core");
        ModuleDeclaration core = module("com.core", "com.internal");
        ModuleDeclaration internal = module("com.internal");
        ModuleGraph graph = new ModuleGraph(Map.of(
                "com.app", app, "com.core", core, "com.internal", internal));

        assertTrue(graph.readableModules("com.app").contains("com.core"));
        assertFalse(graph.readableModules("com.app").contains("com.internal"));
    }

    @Test
    void detectsCyclesAndReportsThemOnce() {
        ModuleDeclaration a = module("com.a", "com.b");
        ModuleDeclaration b = module("com.b", "com.c");
        ModuleDeclaration c = module("com.c", "com.a");
        ModuleGraph graph = new ModuleGraph(
                Map.of("com.a", a, "com.b", b, "com.c", c));

        List<Diagnostic> diagnostics = new CycleDetector().detect(graph);
        assertEquals(1, diagnostics.size());
        assertEquals("CIRCULAR_DEPENDENCY", diagnostics.get(0).code());
        assertEquals(Diagnostic.Severity.ERROR, diagnostics.get(0).severity());
        assertTrue(diagnostics.get(0).message()
                .contains("com.a -> com.b -> com.c -> com.a"));
        assertTrue(new CycleDetector().modulesInCycles(graph)
                .containsAll(java.util.Set.of("com.a", "com.b", "com.c")));
    }

    @Test
    void detectsSelfLoop() {
        ModuleDeclaration self = module("com.self", "com.self");
        ModuleGraph graph = new ModuleGraph(Map.of("com.self", self));
        assertEquals(1, new CycleDetector().detect(graph).size());
    }

    @Test
    void acyclicGraphProducesNoCycleDiagnostics() {
        ModuleGraph graph = new ModuleGraph(Map.of(
                "com.a", module("com.a", "com.b"),
                "com.b", module("com.b")));
        assertTrue(new CycleDetector().detect(graph).isEmpty());
    }
}

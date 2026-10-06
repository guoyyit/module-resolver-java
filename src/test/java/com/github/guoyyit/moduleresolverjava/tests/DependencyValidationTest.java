package com.github.guoyyit.moduleresolverjava.tests;

import com.github.guoyyit.moduleresolverjava.analysis.PackageConflictResolver;
import com.github.guoyyit.moduleresolverjava.analysis.ServiceBinder;
import com.github.guoyyit.moduleresolverjava.graph.CycleDetector;
import com.github.guoyyit.moduleresolverjava.graph.DependencyGraph;
import com.github.guoyyit.moduleresolverjava.model.Diagnostic;
import com.github.guoyyit.moduleresolverjava.model.FileParseOutcome;
import com.github.guoyyit.moduleresolverjava.model.ModuleDeclaration;
import com.github.guoyyit.moduleresolverjava.model.PackageOwnership;
import com.github.guoyyit.moduleresolverjava.model.ServiceBinding;
import com.github.guoyyit.moduleresolverjava.parser.ManifestParser;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DependencyValidationTest {

    private static Map<String, ModuleDeclaration> parse(String... nameAndContent) {
        ManifestParser parser = new ManifestParser();
        Map<String, ModuleDeclaration> declarations = new LinkedHashMap<>();
        for (int i = 0; i < nameAndContent.length; i += 2) {
            String file = nameAndContent[i] + ".module";
            FileParseOutcome outcome = parser.parse(file, nameAndContent[i + 1]);
            assertTrue(outcome.success(), () -> "parse failed: " + outcome.diagnostics());
            declarations.put(outcome.declaration().name(), outcome.declaration());
        }
        return declarations;
    }

    private static DependencyGraph graphOf(Map<String, ModuleDeclaration> declarations) {
        Map<String, List<String>> edges = new LinkedHashMap<>();
        for (ModuleDeclaration declaration : declarations.values()) {
            edges.put(declaration.name(),
                    declaration.requires().stream().map(req -> req.name()).toList());
        }
        return DependencyGraph.from(edges);
    }

    private static Map<String, List<String>> readableOf(
            Map<String, ModuleDeclaration> declarations) {
        return com.github.guoyyit.moduleresolverjava.graph.Readability
                .computeAll(declarations).readable();
    }

    @Test
    void detectsDirectAndTransitiveDependencies() {
        Map<String, ModuleDeclaration> declarations = parse(
                "app", "module com.app\nrequires com.biz\n",
                "biz", "module com.biz\nrequires com.core\n",
                "core", "module com.core\n");
        DependencyGraph graph = graphOf(declarations);

        assertEquals(List.of("com.biz"), graph.directDependencies("com.app"));
        assertEquals(List.of("com.core"), graph.transitiveDependencies("com.app"));
        assertEquals(List.of("com.core"), graph.directDependencies("com.biz"));
        assertTrue(graph.transitiveDependencies("com.core").isEmpty());
    }

    @Test
    void detectsCycleDeterministically() {
        Map<String, ModuleDeclaration> declarations = parse(
                "a", "module com.a\nrequires com.b\n",
                "b", "module com.b\nrequires com.c\n",
                "c", "module com.c\nrequires com.a\n");
        CycleDetector detector = new CycleDetector();

        List<List<String>> cycles1 = detector.findCycles(graphOf(declarations));
        List<List<String>> cycles2 = detector.findCycles(graphOf(declarations));

        assertEquals(1, cycles1.size());
        assertEquals(List.of("com.a", "com.b", "com.c", "com.a"), cycles1.get(0));
        assertEquals(cycles1, cycles2);

        Map<String, Set<List<String>>> byModule = detector.cyclesByModule(graphOf(declarations));
        assertEquals(1, byModule.get("com.a").size());
        assertTrue(byModule.get("com.a").contains(cycles1.get(0)));
    }

    @Test
    void detectsSelfCycle() {
        Map<String, ModuleDeclaration> declarations = parse(
                "self", "module com.self\nrequires com.self\n");
        List<List<String>> cycles = new CycleDetector().findCycles(graphOf(declarations));
        assertEquals(List.of(List.of("com.self", "com.self")), cycles);
    }

    @Test
    void resolvesPackageExportsByDeterministicRules() {
        Map<String, ModuleDeclaration> unique = parse(
                "a", "module com.a\nexports pkg.shared\n",
                "b", "module com.b\n");
        PackageOwnership uniqueOwner =
                new PackageConflictResolver().resolve(unique).get("pkg.shared");
        assertEquals("com.a", uniqueOwner.owner());
        assertFalse(uniqueOwner.conflict());
        assertEquals("UNIQUE_EXPORTER", uniqueOwner.rule());

        Map<String, ModuleDeclaration> parentWins = parse(
                "app", "module com.app\nrequires com.core\nexports pkg.shared\n",
                "core", "module com.core\nexports pkg.shared\n");
        PackageOwnership parentOwner =
                new PackageConflictResolver().resolve(parentWins).get("pkg.shared");
        assertEquals("com.core", parentOwner.owner());
        assertEquals("PARENT_WINS", parentOwner.rule());
        assertTrue(parentOwner.diagnostics().stream()
                .anyMatch(d -> "SHADOWED_EXPORT".equals(d.code())
                        && d.severity() == Diagnostic.Severity.WARNING));

        Map<String, ModuleDeclaration> ambiguous = parse(
                "a", "module com.a\nexports pkg.shared\n",
                "b", "module com.b\nexports pkg.shared\n");
        PackageOwnership ambiguousOwner =
                new PackageConflictResolver().resolve(ambiguous).get("pkg.shared");
        assertEquals("AMBIGUOUS_EXPORT", ambiguousOwner.rule());
        assertEquals("com.a", ambiguousOwner.owner());
        assertTrue(ambiguousOwner.diagnostics().stream()
                .anyMatch(d -> d.severity() == Diagnostic.Severity.ERROR
                        && "AMBIGUOUS_EXPORT".equals(d.code())));
    }

    @Test
    void bindsServicesAndWarnsOnAmbiguousProviders() {
        Map<String, ModuleDeclaration> declarations = parse(
                "app", """
                        module com.app
                        requires com.provider
                        consumes api.Greeter
                        """,
                "provider", """
                        module com.provider
                        exports api
                        provides api.Greeter with impl.GreeterOne, impl.GreeterTwo
                        """);
        DependencyGraph graph = graphOf(declarations);
        Map<String, PackageOwnership> ownerships =
                new PackageConflictResolver().resolve(declarations);
        Map<String, List<ServiceBinding>> bindings =
                new ServiceBinder().bind(declarations, graph, ownerships,
                        readableOf(declarations));

        ServiceBinding binding = bindings.get("com.app").get(0);
        assertEquals("api.Greeter", binding.serviceInterface());
        assertEquals("com.provider", binding.selectedProvider());
        assertEquals("impl.GreeterOne", binding.selectedImplementation());
        assertEquals("BOUND_AMBIGUOUS", binding.status());
        assertTrue(binding.diagnostics().stream()
                .anyMatch(d -> "AMBIGUOUS_PROVIDER".equals(d.code())));
    }

    @Test
    void missingRequiredServiceIsErrorAndOptionalIsWarning() {
        Map<String, ModuleDeclaration> declarations = parse(
                "app", """
                        module com.app
                        consumes api.Required
                        consumes optional api.Optional
                        """);
        DependencyGraph graph = graphOf(declarations);
        Map<String, List<ServiceBinding>> bindings = new ServiceBinder()
                .bind(declarations, graph, new PackageConflictResolver().resolve(declarations),
                        readableOf(declarations));

        ServiceBinding required = bindings.get("com.app").stream()
                .filter(b -> b.serviceInterface().equals("api.Required")).findFirst().orElseThrow();
        ServiceBinding optional = bindings.get("com.app").stream()
                .filter(b -> b.serviceInterface().equals("api.Optional")).findFirst().orElseThrow();
        assertEquals("MISSING_REQUIRED", required.status());
        assertNull(required.selectedProvider());
        assertEquals(Diagnostic.Severity.ERROR, required.diagnostics().get(0).severity());
        assertEquals("MISSING_OPTIONAL", optional.status());
        assertEquals(Diagnostic.Severity.WARNING, optional.diagnostics().get(0).severity());
    }

    @Test
    void missingOptionalDependencyDoesNotBlockResolution() {
        Map<String, ModuleDeclaration> declarations = parse(
                "app", "module com.app\nrequires static com.maybe\nexports pkg.app\n");
        DependencyGraph graph = graphOf(declarations);
        assertEquals(List.of("com.maybe"), graph.directDependencies("com.app"));
        assertTrue(graph.transitiveDependencies("com.app").isEmpty());
    }
}

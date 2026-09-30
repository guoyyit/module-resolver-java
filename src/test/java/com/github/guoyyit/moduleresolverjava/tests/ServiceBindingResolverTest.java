package com.github.guoyyit.moduleresolverjava.tests;

import com.github.guoyyit.moduleresolverjava.analysis.ServiceBindingResolver;
import com.github.guoyyit.moduleresolverjava.graph.ModuleGraph;
import com.github.guoyyit.moduleresolverjava.model.Diagnostic;
import com.github.guoyyit.moduleresolverjava.model.ModuleDeclaration;
import com.github.guoyyit.moduleresolverjava.model.ProvideDirective;
import com.github.guoyyit.moduleresolverjava.model.RequireDirective;
import com.github.guoyyit.moduleresolverjava.model.SourceLocation;
import com.github.guoyyit.moduleresolverjava.model.UseDirective;
import com.github.guoyyit.moduleresolverjava.resolve.ServiceBinding;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServiceBindingResolverTest {

    private static final SourceLocation LOCATION =
            new SourceLocation("test.mod", 2, 5);

    private ModuleDeclaration consumer(String module) {
        return TestModules.full(module, List.of(), List.of(), List.of(),
                List.of(new UseDirective("svc.Api", LOCATION)));
    }

    private ModuleDeclaration provider(String module) {
        return TestModules.full(module, List.of(), List.of(),
                List.of(new ProvideDirective("svc.Api", module + ".Impl", LOCATION)),
                List.of());
    }

    @Test
    void bindsConsumerToProvider() {
        ModuleGraph graph = new ModuleGraph(Map.of(
                "com.consumer", consumer("com.consumer"),
                "com.provider", provider("com.provider")));
        ServiceBindingResolver resolver = new ServiceBindingResolver();

        ServiceBinding binding = resolver.resolve(graph).get("svc.Api");
        assertEquals(List.of("com.provider"), binding.providerModules());
        assertEquals("com.provider", binding.selectedProvider());
        assertTrue(resolver.validate(graph).isEmpty());
    }

    @Test
    void multipleProvidersSelectSmallestAndWarn() {
        ModuleGraph graph = new ModuleGraph(Map.of(
                "com.consumer", consumer("com.consumer"),
                "com.beta", provider("com.beta"),
                "com.alpha", provider("com.alpha")));
        ServiceBindingResolver resolver = new ServiceBindingResolver();

        ServiceBinding binding = resolver.resolve(graph).get("svc.Api");
        assertEquals("com.alpha", binding.selectedProvider());
        List<Diagnostic> diagnostics = resolver.validate(graph);
        assertEquals(1, diagnostics.size());
        assertEquals("SERVICE_MULTIPLE_PROVIDERS", diagnostics.get(0).code());
        assertEquals(Diagnostic.Severity.WARNING, diagnostics.get(0).severity());
        assertTrue(diagnostics.get(0).message().contains("com.alpha"));
    }

    @Test
    void missingProviderIsErrorByDefault() {
        ModuleGraph graph = new ModuleGraph(Map.of(
                "com.consumer", consumer("com.consumer")));
        List<Diagnostic> diagnostics = new ServiceBindingResolver().validate(graph);

        assertEquals(1, diagnostics.size());
        assertEquals("SERVICE_NO_PROVIDER", diagnostics.get(0).code());
        assertEquals(Diagnostic.Severity.ERROR, diagnostics.get(0).severity());
        assertEquals(2, diagnostics.get(0).line());
    }

    @Test
    void optionalMissingDependencyIsInfoAndNotBlocking() {
        ModuleDeclaration consumer = TestModules.full("com.consumer",
                List.of(new RequireDirective("com.maybe", false, true, LOCATION)),
                List.of(), List.of(),
                List.of(new UseDirective("svc.Api", LOCATION)));
        ModuleGraph graph = new ModuleGraph(Map.of("com.consumer", consumer));
        List<Diagnostic> diagnostics = new ServiceBindingResolver().validate(graph);

        assertEquals(1, diagnostics.size());
        assertTrue(diagnostics.stream().anyMatch(diagnostic ->
                diagnostic.code().equals("SERVICE_OPTIONAL_MISSING")
                        && diagnostic.severity() == Diagnostic.Severity.INFO));
        assertTrue(diagnostics.stream().noneMatch(diagnostic ->
                diagnostic.severity() == Diagnostic.Severity.ERROR));
    }
}

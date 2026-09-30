package com.github.guoyyit.moduleresolverjava.analysis;

import com.github.guoyyit.moduleresolverjava.graph.ModuleGraph;
import com.github.guoyyit.moduleresolverjava.model.ModuleDeclaration;
import com.github.guoyyit.moduleresolverjava.model.ProvideDirective;
import com.github.guoyyit.moduleresolverjava.model.RequireDirective;
import com.github.guoyyit.moduleresolverjava.model.Diagnostic;
import com.github.guoyyit.moduleresolverjava.model.UseDirective;
import com.github.guoyyit.moduleresolverjava.resolve.ServiceBinding;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Validates {@code uses}/{@code provides} service bindings.
 *
 * <p>Rules:
 * <ul>
 *   <li>no provider for a consumed interface: {@code SERVICE_NO_PROVIDER}
 *       error, unless every {@code requires} path from the consumer is
 *       optional (then {@code SERVICE_OPTIONAL_MISSING} info only);</li>
 *   <li>multiple providers for one interface: the lexicographically
 *       smallest provider module wins deterministically and an
 *       {@code SERVICE_MULTIPLE_PROVIDERS} warning lists all candidates;</li>
 *   <li>each chosen binding is returned in the resolution result.</li>
 * </ul>
 */
public final class ServiceBindingResolver {

    /** One binding per service interface that is consumed or provided (sorted). */
    public Map<String, ServiceBinding> resolve(ModuleGraph graph) {
        Map<String, Set<String>> consumers = new TreeMap<>();
        Map<String, Set<String>> providers = new TreeMap<>();
        for (String module : new TreeSet<>(graph.moduleNames())) {
            ModuleDeclaration declaration = graph.declaration(module);
            for (UseDirective use : declaration.uses()) {
                consumers.computeIfAbsent(use.serviceInterface(), key -> new TreeSet<>())
                        .add(module);
            }
            for (ProvideDirective provide : declaration.provides()) {
                providers.computeIfAbsent(provide.serviceInterface(), key -> new TreeSet<>())
                        .add(module);
            }
        }
        Map<String, ServiceBinding> bindings = new TreeMap<>();
        Set<String> interfaces = new TreeSet<>();
        interfaces.addAll(consumers.keySet());
        interfaces.addAll(providers.keySet());
        for (String service : interfaces) {
            Set<String> providerSet = providers.getOrDefault(service, Set.of());
            String selected = providerSet.isEmpty() ? null : providerSet.iterator().next();
            bindings.put(service, new ServiceBinding(
                    service,
                    List.copyOf(consumers.getOrDefault(service, Set.of())),
                    List.copyOf(providerSet),
                    selected));
        }
        return Map.copyOf(bindings);
    }

    public List<Diagnostic> validate(ModuleGraph graph) {
        Map<String, ServiceBinding> bindings = resolve(graph);
        List<Diagnostic> diagnostics = new ArrayList<>();
        for (ServiceBinding binding : bindings.values()) {
            if (!binding.providerModules().isEmpty()) {
                if (binding.providerModules().size() > 1) {
                    diagnostics.add(new Diagnostic(
                            Diagnostic.Severity.WARNING,
                            "SERVICE_MULTIPLE_PROVIDERS",
                            "service '" + binding.serviceInterface()
                                    + "' has multiple providers "
                                    + String.join(", ", binding.providerModules())
                                    + "; deterministic selection is '"
                                    + binding.selectedProvider()
                                    + "' (smallest provider module name)",
                            null, null, null));
                }
                continue;
            }
            for (String consumer : binding.consumerModules()) {
                ModuleDeclaration declaration = graph.declaration(consumer);
                UseDirective directive = declaration.uses().stream()
                        .filter(use -> use.serviceInterface().equals(binding.serviceInterface()))
                        .findFirst()
                        .orElseThrow();
                boolean allOptional = onlyDependsOnMissingOptionalModules(graph, consumer);
                if (allOptional) {
                    diagnostics.add(new Diagnostic(
                            Diagnostic.Severity.INFO,
                            "SERVICE_OPTIONAL_MISSING",
                            "service '" + binding.serviceInterface()
                                    + "' consumed by module '" + consumer
                                    + "' has no provider; all missing dependencies are "
                                    + "declared 'static' (optional), resolution continues",
                            declaration.file(),
                            directive.location().line(),
                            directive.location().column()));
                } else {
                    diagnostics.add(new Diagnostic(
                            Diagnostic.Severity.ERROR,
                            "SERVICE_NO_PROVIDER",
                            "service '" + binding.serviceInterface()
                                    + "' consumed by module '" + consumer
                                    + "' has no provider",
                            declaration.file(),
                            directive.location().line(),
                            directive.location().column()));
                }
            }
        }
        diagnostics.sort(ServiceBindingResolver::compare);
        return List.copyOf(diagnostics);
    }

    /**
     * A consumer with no provider is treated as optional only when every
     * missing target of its {@code requires} directives is declared
     * {@code static} and it declares at least one such dependency.
     */
    private static boolean onlyDependsOnMissingOptionalModules(ModuleGraph graph,
                                                               String consumer) {
        ModuleDeclaration declaration = graph.declaration(consumer);
        boolean hasOptional = false;
        for (RequireDirective require : declaration.requires()) {
            if (graph.moduleNames().contains(require.moduleName())) {
                continue;
            }
            if (!require.optional()) {
                return false;
            }
            hasOptional = true;
        }
        return hasOptional;
    }

    private static int compare(Diagnostic a, Diagnostic b) {
        String fileA = a.file() == null ? "" : a.file();
        String fileB = b.file() == null ? "" : b.file();
        int byFile = fileA.compareTo(fileB);
        if (byFile != 0) {
            return byFile;
        }
        int lineA = a.line() == null ? Integer.MAX_VALUE : a.line();
        int lineB = b.line() == null ? Integer.MAX_VALUE : b.line();
        return Integer.compare(lineA, lineB);
    }
}

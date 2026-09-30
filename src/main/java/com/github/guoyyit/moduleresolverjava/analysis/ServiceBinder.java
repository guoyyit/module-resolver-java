package com.github.guoyyit.moduleresolverjava.analysis;

import com.github.guoyyit.moduleresolverjava.graph.DependencyGraph;
import com.github.guoyyit.moduleresolverjava.model.Diagnostic;
import com.github.guoyyit.moduleresolverjava.model.ModuleDeclaration;
import com.github.guoyyit.moduleresolverjava.model.PackageOwnership;
import com.github.guoyyit.moduleresolverjava.model.ProvidedService;
import com.github.guoyyit.moduleresolverjava.model.ServiceBinding;
import com.github.guoyyit.moduleresolverjava.model.ServiceConsumption;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Validates {@code consumes} declarations against visible {@code provides}.
 *
 * <p>Visibility rule: a consumer M may use an interface provided by module P
 * when M == P or M depends on P (directly or transitively). Candidate
 * providers are further required to export the interface's package (the
 * package owner must export it to M through the chain; package ownership
 * itself is validated by {@link PackageConflictResolver}).
 *
 * <p>Selection rule for multiple visible providers:
 * <ol>
 *   <li>{@code DIRECT_PROVIDER}: providers required directly take precedence
 *       over transitively required ones.</li>
 *   <li>{@code LEXICOGRAPHIC}: the lexicographically smallest
 *       {@code (provider-module, implementation-class)} pair wins.</li>
 * </ol>
 * If more than one provider remains at the highest precedence level the
 * binding still uses the deterministic winner but carries an
 * {@code AMBIGUOUS_PROVIDER} {@code WARNING}.
 *
 * <p>Missing providers produce {@code MISSING_PROVIDER} as {@code ERROR} for
 * required consumes and {@code WARNING} for optional ones; optional misses
 * never block overall resolution.
 */
public final class ServiceBinder {

    public Map<String, List<ServiceBinding>> bind(Map<String, ModuleDeclaration> declarations,
                                                  DependencyGraph graph,
                                                  Map<String, PackageOwnership> packageOwners) {
        Map<String, List<ServiceBinding>> result = new TreeMap<>();

        for (ModuleDeclaration consumerDecl : new TreeMap<>(declarations).values()) {
            String consumer = consumerDecl.name();
            List<ServiceBinding> bindings = new ArrayList<>();
            for (ServiceConsumption consumption : consumerDecl.consumes()) {
                bindings.add(bindOne(consumer, consumption, declarations, graph, packageOwners));
            }
            if (!bindings.isEmpty()) {
                result.put(consumer, List.copyOf(bindings));
            }
        }
        return Map.copyOf(result);
    }

    private static ServiceBinding bindOne(String consumer,
                                          ServiceConsumption consumption,
                                          Map<String, ModuleDeclaration> declarations,
                                          DependencyGraph graph,
                                          Map<String, PackageOwnership> packageOwners) {
        String serviceInterface = consumption.serviceInterface();
        List<Candidate> candidates = new ArrayList<>();

        Set<String> direct = new TreeSet<>(graph.directDependencies(consumer));
        Set<String> visible = new TreeSet<>(direct);
        visible.addAll(graph.transitiveDependencies(consumer));

        for (ModuleDeclaration providerDecl : declarations.values()) {
            String provider = providerDecl.name();
            boolean sameModule = provider.equals(consumer);
            if (!sameModule && !visible.contains(provider)) {
                continue;
            }
            for (ProvidedService provided : providerDecl.provides()) {
                if (!provided.serviceInterface().equals(serviceInterface)) {
                    continue;
                }
                if (!packageExportedToConsumer(providerDecl, serviceInterface,
                        consumer, sameModule, declarations, packageOwners)) {
                    continue;
                }
                candidates.add(new Candidate(provider, provided.implementation(),
                        sameModule || direct.contains(provider), provided.position()));
            }
        }

        List<String> candidateKeys = new ArrayList<>();
        for (Candidate candidate : candidates) {
            candidateKeys.add(candidate.provider + "#" + candidate.implementation);
        }

        if (candidates.isEmpty()) {
            Diagnostic.Severity severity = consumption.optional()
                    ? Diagnostic.Severity.WARNING : Diagnostic.Severity.ERROR;
            String message = consumption.optional()
                    ? "no visible provider for optional service '" + serviceInterface + "'"
                    : "no visible provider for required service '" + serviceInterface + "'";
            Diagnostic diagnostic = new Diagnostic(severity, "MISSING_PROVIDER",
                    message, consumption.position());
            return new ServiceBinding(consumer, serviceInterface, consumption.optional(),
                    null, null, List.of(),
                    consumption.optional() ? "MISSING_OPTIONAL" : "MISSING_REQUIRED",
                    List.of(diagnostic));
        }

        List<Candidate> bestLevel = new ArrayList<>();
        boolean anyDirect = candidates.stream().anyMatch(candidate -> candidate.direct);
        for (Candidate candidate : candidates) {
            if (candidate.direct == anyDirect) {
                bestLevel.add(candidate);
            }
        }
        bestLevel.sort((a, b) -> {
            int byProvider = a.provider.compareTo(b.provider);
            return byProvider != 0 ? byProvider : a.implementation.compareTo(b.implementation);
        });

        Candidate winner = bestLevel.get(0);
        List<Diagnostic> diagnostics = new ArrayList<>();
        String status = "BOUND";
        if (bestLevel.size() > 1) {
            status = "BOUND_AMBIGUOUS";
            List<String> names = bestLevel.stream()
                    .map(candidate -> candidate.provider + "#" + candidate.implementation).toList();
            diagnostics.add(new Diagnostic(Diagnostic.Severity.WARNING, "AMBIGUOUS_PROVIDER",
                    "service '" + serviceInterface + "' has " + bestLevel.size()
                            + " equally visible providers " + names
                            + "; deterministic selection picked '"
                            + winner.provider + "#" + winner.implementation + "'",
                    consumption.position()));
        }
        if (anyDirect && candidates.size() > bestLevel.size()) {
            diagnostics.add(new Diagnostic(Diagnostic.Severity.INFO, "DIRECT_PROVIDER_PREFERRED",
                    "direct provider chosen over transitive providers for service '"
                            + serviceInterface + "'", consumption.position()));
        }

        return new ServiceBinding(consumer, serviceInterface, consumption.optional(),
                winner.provider, winner.implementation, List.copyOf(candidateKeys),
                status, List.copyOf(diagnostics));
    }

    private static boolean packageExportedToConsumer(ModuleDeclaration providerDecl,
                                                     String serviceInterface,
                                                     String consumer,
                                                     boolean sameModule,
                                                     Map<String, ModuleDeclaration> declarations,
                                                     Map<String, PackageOwnership> packageOwners) {
        String pkg = packageOf(serviceInterface);
        if (pkg == null) {
            return false;
        }
        boolean exportedByProvider = providerDecl.exports().stream()
                .anyMatch(exported -> exported.packageName().equals(pkg));
        if (!exportedByProvider) {
            return false;
        }
        if (sameModule) {
            return true;
        }
        PackageOwnership ownership = packageOwners.get(pkg);
        if (ownership == null) {
            return false;
        }
        return ownership.owner().equals(providerDecl.name())
                && !"AMBIGUOUS_EXPORT".equals(ownership.rule());
    }

    private static String packageOf(String className) {
        int dot = className.lastIndexOf('.');
        return dot < 0 ? null : className.substring(0, dot);
    }

    private record Candidate(String provider, String implementation,
                             boolean direct, com.github.guoyyit.moduleresolverjava.model.Position position) {
    }
}

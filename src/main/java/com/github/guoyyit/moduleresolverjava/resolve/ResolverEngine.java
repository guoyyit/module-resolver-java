package com.github.guoyyit.moduleresolverjava.resolve;

import com.github.guoyyit.moduleresolverjava.analysis.CycleDetector;
import com.github.guoyyit.moduleresolverjava.analysis.ExportOwnership;
import com.github.guoyyit.moduleresolverjava.analysis.ServiceBindingResolver;
import com.github.guoyyit.moduleresolverjava.graph.ModuleGraph;
import com.github.guoyyit.moduleresolverjava.model.Diagnostic;
import com.github.guoyyit.moduleresolverjava.model.ModuleDeclaration;
import com.github.guoyyit.moduleresolverjava.model.RequireDirective;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Pure, side-effect-free resolution logic. Turns the current parsed
 * declarations plus per-file parse failures into a complete immutable
 * snapshot. Graph-level structures (cycles, export ownership, service
 * bindings, diagnostics) are always recomputed globally with deterministic
 * ordering, while per-module results are reused for modules outside the
 * affected set so unchanged modules keep byte-stable conclusions.
 */
public final class ResolverEngine {

    public static final int HISTORY_LIMIT = 50;

    private final CycleDetector cycleDetector = new CycleDetector();
    private final ExportOwnership exportOwnership = new ExportOwnership();
    private final ServiceBindingResolver serviceBindingResolver = new ServiceBindingResolver();

    /**
     * @param changedModuleNames modules whose own declaration changed plus
     *                           {@code null} meaning "unknown / first run,
     *                           recompute everything"
     */
    public ResolutionSnapshot resolve(long version,
                                      Map<String, String> fileContentHashes,
                                      Map<String, ModuleDeclaration> declarationsByFile,
                                      Map<String, String> parseFailures,
                                      Map<String, String> failedFileHashes,
                                      Set<String> changedModuleNames,
                                      ResolutionSnapshot previous,
                                      CommitEntry commitEntry,
                                      CancellationToken token) {
        DuplicateCheck duplicateCheck = checkDuplicateNames(declarationsByFile);
        Map<String, ModuleDeclaration> byName = duplicateCheck.byName;

        ModuleGraph graph = new ModuleGraph(byName);
        checkCancelled(token);

        Set<String> cyclicModules = cycleDetector.modulesInCycles(graph);
        List<Diagnostic> diagnostics = new ArrayList<>();
        diagnostics.addAll(duplicateCheck.diagnostics);
        diagnostics.addAll(missingRequires(graph));
        diagnostics.addAll(cycleDetector.detect(graph));
        diagnostics.addAll(exportOwnership.conflicts(graph));
        diagnostics.addAll(serviceBindingResolver.validate(graph));

        Map<String, Set<String>> packageExporters = exportOwnership.exporters(graph);
        Set<String> affected = changedModuleNames == null
                ? null
                : affectedModules(changedModuleNames, graph, packageExporters,
                        declarationsByFile);
        if (affected == null) {
            // Full resolution: bulk O(V+E) pre-computation pays off because
            // every module result is built.
            graph.precompute();
        }

        Map<String, ResolvedModule> resolvedModules = new TreeMap<>();
        for (String module : new TreeSet<>(graph.moduleNames())) {
            ResolvedModule existing = previous == null ? null
                    : previous.modules().get(module);
            if (existing == null || affected == null || affected.contains(module)) {
                resolvedModules.put(module, toResolvedModule(graph, module, cyclicModules));
            } else {
                resolvedModules.put(module, existing);
            }
            checkCancelled(token);
        }

        List<CommitEntry> history = new ArrayList<>();
        if (previous != null) {
            history.addAll(previous.history());
        }
        history.add(commitEntry);
        if (history.size() > HISTORY_LIMIT) {
            history = history.subList(history.size() - HISTORY_LIMIT, history.size());
        }

        return new ResolutionSnapshot(
                version,
                Instant.now(),
                Map.copyOf(resolvedModules),
                buildModuleFiles(declarationsByFile),
                Map.copyOf(fileContentHashes),
                Map.copyOf(failedFileHashes),
                exportOwnership.resolveOwners(graph),
                serviceBindingResolver.resolve(graph),
                sorted(diagnostics),
                Map.copyOf(parseFailures),
                List.copyOf(history));
    }

    /** Stable ordering for all emitted diagnostics. */
    public static List<Diagnostic> sorted(List<Diagnostic> diagnostics) {
        List<Diagnostic> copy = new ArrayList<>(diagnostics);
        copy.sort(Comparator
                .comparing(Diagnostic::severity)
                .thenComparing(diagnostic -> nullSafe(diagnostic.file()))
                .thenComparingInt(diagnostic ->
                        diagnostic.line() == null ? Integer.MAX_VALUE : diagnostic.line())
                .thenComparingInt(diagnostic ->
                        diagnostic.column() == null ? Integer.MAX_VALUE : diagnostic.column())
                .thenComparing(Diagnostic::code)
                .thenComparing(Diagnostic::message));
        return List.copyOf(copy);
    }

    private static Map<String, String> buildModuleFiles(
            Map<String, ModuleDeclaration> declarationsByFile) {
        Map<String, String> moduleFiles = new TreeMap<>();
        for (ModuleDeclaration declaration : declarationsByFile.values()) {
            moduleFiles.put(declaration.name(), declaration.file());
        }
        return Map.copyOf(moduleFiles);
    }

    private static DuplicateCheck checkDuplicateNames(
            Map<String, ModuleDeclaration> declarationsByFile) {
        Map<String, ModuleDeclaration> byName = new HashMap<>();
        Map<String, List<ModuleDeclaration>> grouped = new HashMap<>();
        for (ModuleDeclaration declaration : declarationsByFile.values()) {
            byName.putIfAbsent(declaration.name(), declaration);
            grouped.computeIfAbsent(declaration.name(), key -> new ArrayList<>())
                    .add(declaration);
        }
        List<Diagnostic> diagnostics = new ArrayList<>();
        for (Map.Entry<String, List<ModuleDeclaration>> entry : grouped.entrySet()) {
            if (entry.getValue().size() <= 1) {
                continue;
            }
            entry.getValue().sort(Comparator.comparing(ModuleDeclaration::file));
            ModuleDeclaration winner = entry.getValue().get(0);
            for (ModuleDeclaration declaration : entry.getValue()) {
                if (declaration == winner) {
                    continue;
                }
                diagnostics.add(new Diagnostic(
                        Diagnostic.Severity.ERROR,
                        "DUPLICATE_MODULE",
                        "module name '" + entry.getKey() + "' is also declared in "
                                + winner.file() + "; deterministic keeper is that file "
                                + "(smallest path), this declaration is ignored",
                        declaration.file(),
                        declaration.nameLocation().line(),
                        declaration.nameLocation().column()));
            }
        }
        return new DuplicateCheck(byName, diagnostics);
    }

    private record DuplicateCheck(Map<String, ModuleDeclaration> byName,
                                  List<Diagnostic> diagnostics) {
    }

    /**
     * Computes the modules whose per-module result may change: the changed
     * modules themselves, everything in their cycle SCC, their reverse
     * dependency closure, co-exporters of packages they export, and the
     * other side of service relationships they participate in.
     */
    private static Set<String> affectedModules(Set<String> seeds,
                                               ModuleGraph graph,
                                               Map<String, Set<String>> packageExporters,
                                               Map<String, ModuleDeclaration> declarationsByFile) {
        Set<String> affected = new HashSet<>(seeds);
        Set<String> frontier = new HashSet<>(seeds);
        while (!frontier.isEmpty()) {
            Set<String> next = new HashSet<>();
            for (String module : frontier) {
                for (String dependent : graph.directDependents(module)) {
                    if (affected.add(dependent)) {
                        next.add(dependent);
                    }
                }
            }
            frontier = next;
        }
        for (String seed : List.copyOf(seeds)) {
            ModuleDeclaration declaration = graph.declaration(seed);
            if (declaration == null) {
                continue;
            }
            for (String exportedPackage : new TreeSet<>(declaration.exports().stream()
                    .map(export -> export.packageName()).toList())) {
                affected.addAll(packageExporters.getOrDefault(
                        exportedPackage, Set.of()));
            }
            Set<String> servicePeers = servicePeers(declarationsByFile, declaration);
            affected.addAll(servicePeers);
        }
        return affected;
    }

    private static Set<String> servicePeers(
            Map<String, ModuleDeclaration> declarationsByFile,
            ModuleDeclaration declaration) {
        Set<String> interfaces = new TreeSet<>();
        declaration.uses().forEach(use -> interfaces.add(use.serviceInterface()));
        declaration.provides().forEach(provide ->
                interfaces.add(provide.serviceInterface()));
        Set<String> peers = new TreeSet<>();
        for (ModuleDeclaration other : declarationsByFile.values()) {
            boolean related = other.uses().stream()
                    .anyMatch(use -> interfaces.contains(use.serviceInterface()))
                    || other.provides().stream()
                    .anyMatch(provide -> interfaces.contains(provide.serviceInterface()));
            if (related) {
                peers.add(other.name());
            }
        }
        return peers;
    }

    private static List<Diagnostic> missingRequires(ModuleGraph graph) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        for (String module : new TreeSet<>(graph.moduleNames())) {
            ModuleDeclaration declaration = graph.declaration(module);
            for (RequireDirective require : declaration.requires()) {
                if (graph.moduleNames().contains(require.moduleName())) {
                    continue;
                }
                if (require.optional()) {
                    diagnostics.add(new Diagnostic(
                            Diagnostic.Severity.INFO,
                            "REQUIRES_OPTIONAL_MISSING",
                            "optional dependency '" + require.moduleName()
                                    + "' required by module '" + module
                                    + "' is absent; resolution continues",
                            declaration.file(),
                            require.location().line(),
                            require.location().column()));
                } else {
                    diagnostics.add(new Diagnostic(
                            Diagnostic.Severity.ERROR,
                            "REQUIRES_MISSING",
                            "module '" + module + "' requires '"
                                    + require.moduleName() + "' which is not declared",
                            declaration.file(),
                            require.location().line(),
                            require.location().column()));
                }
            }
        }
        return diagnostics;
    }

    private static ResolvedModule toResolvedModule(ModuleGraph graph,
                                                   String module,
                                                   Set<String> cyclicModules) {
        ModuleDeclaration declaration = graph.declaration(module);
        return new ResolvedModule(
                module,
                declaration.file(),
                declaration.contentHash(),
                List.copyOf(graph.directDependencies(module)),
                List.copyOf(graph.transitiveDependencies(module)),
                List.copyOf(graph.readableModules(module).stream().sorted().toList()),
                declaration.exports().stream()
                        .map(export -> export.packageName())
                        .sorted()
                        .toList(),
                cyclicModules.contains(module));
    }

    private static void checkCancelled(CancellationToken token) {
        if (token != null && token.isCancelled()) {
            throw new ResolutionCancelledException();
        }
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}

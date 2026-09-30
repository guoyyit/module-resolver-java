package com.github.guoyyit.moduleresolverjava.engine;

import com.github.guoyyit.moduleresolverjava.analysis.PackageConflictResolver;
import com.github.guoyyit.moduleresolverjava.analysis.ServiceBinder;
import com.github.guoyyit.moduleresolverjava.graph.CycleDetector;
import com.github.guoyyit.moduleresolverjava.graph.DependencyGraph;
import com.github.guoyyit.moduleresolverjava.model.Diagnostic;
import com.github.guoyyit.moduleresolverjava.model.FileParseOutcome;
import com.github.guoyyit.moduleresolverjava.model.ModuleDeclaration;
import com.github.guoyyit.moduleresolverjava.model.ModuleDependency;
import com.github.guoyyit.moduleresolverjava.model.ModuleResolution;
import com.github.guoyyit.moduleresolverjava.model.PackageOwnership;
import com.github.guoyyit.moduleresolverjava.model.Position;
import com.github.guoyyit.moduleresolverjava.model.ResolutionSnapshot;
import com.github.guoyyit.moduleresolverjava.model.ServiceBinding;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Pure, deterministic resolution logic with no I/O and no persistence.
 *
 * <p>A run consumes file-level parse outcomes and produces one immutable
 * {@link ResolutionSnapshot}. The same inputs always produce the same output,
 * independent of submission order or concurrency, because every collection
 * is normalized to deterministic ordering inside the engine.
 */
public final class ResolutionEngine {

    private final PackageConflictResolver packageConflictResolver = new PackageConflictResolver();
    private final ServiceBinder serviceBinder = new ServiceBinder();
    private final CycleDetector cycleDetector = new CycleDetector();

    public ResolutionResult fullResolve(Map<String, FileParseOutcome> fileOutcomes,
                                        long baseVersion) {
        return resolve(fileOutcomes, baseVersion, Set.of(), null);
    }

    /**
     * Bulk replacement of the whole file set; versions keep advancing from
     * the previous snapshot and every module is treated as affected.
     */
    public ResolutionResult rebuild(ResolutionSnapshot previous,
                                    Map<String, FileParseOutcome> fileOutcomes) {
        long baseVersion = previous == null ? 0 : previous.version();
        return resolve(fileOutcomes, baseVersion, Set.copyOf(fileOutcomes.keySet()), null);
    }

    public ResolutionResult incrementalResolve(ResolutionSnapshot previous,
                                               Map<String, FileParseOutcome> changedFileOutcomes,
                                               Set<String> removedFiles) {
        Map<String, FileParseOutcome> merged = new TreeMap<>(previous.files());
        changedFileOutcomes.forEach(merged::put);
        removedFiles.forEach(merged::remove);
        Set<String> touched = new TreeSet<>(changedFileOutcomes.keySet());
        touched.addAll(removedFiles);
        return resolve(merged, previous.version(), Set.copyOf(touched), previous);
    }

    private ResolutionResult resolve(Map<String, FileParseOutcome> fileOutcomes,
                                     long baseVersion,
                                     Set<String> changedFiles,
                                     ResolutionSnapshot previous) {
        if (Thread.currentThread().isInterrupted()) {
            return new ResolutionResult(null, Set.of(), Map.of(), true);
        }
        Map<String, FileParseOutcome> files = new TreeMap<>(fileOutcomes);

        Map<String, ModuleDeclaration> declarations = new TreeMap<>();
        List<Diagnostic> globalDiagnostics = new ArrayList<>();
        addParseDiagnostics(files, globalDiagnostics);
        detectDuplicateModuleNames(files, declarations, globalDiagnostics);

        DependencyGraph graph = buildGraph(declarations, globalDiagnostics);
        if (Thread.currentThread().isInterrupted()) {
            return new ResolutionResult(null, Set.of(), Map.of(), true);
        }

        Map<String, PackageOwnership> packageOwners =
                packageConflictResolver.resolve(declarations);
        if (Thread.currentThread().isInterrupted()) {
            return new ResolutionResult(null, Set.of(), Map.of(), true);
        }
        Map<String, List<ServiceBinding>> serviceBindings =
                serviceBinder.bind(declarations, graph, packageOwners);
        if (Thread.currentThread().isInterrupted()) {
            return new ResolutionResult(null, Set.of(), Map.of(), true);
        }

        List<List<String>> cycles = cycleDetector.findCycles(graph);
        Map<String, Set<List<String>>> cyclesByModule = cycleDetector.cyclesByModule(graph);

        Map<String, ModuleResolution> modules = buildModuleResolutions(
                declarations, graph, cyclesByModule, packageOwners, serviceBindings);

        addPackageDiagnostics(packageOwners, globalDiagnostics);
        addServiceDiagnostics(serviceBindings, globalDiagnostics);
        addCycleDiagnostics(cycles, globalDiagnostics);

        Set<String> affected = previous == null
                ? Set.copyOf(declarations.keySet())
                : computeAffected(previous, changedFiles, files, declarations,
                        packageOwners, serviceBindings);

        ResolutionSnapshot snapshot = new ResolutionSnapshot(
                baseVersion + 1, Instant.now(),
                Map.copyOf(files),
                Map.copyOf(declarations),
                Map.copyOf(modules),
                List.copyOf(globalDiagnostics),
                Map.copyOf(packageOwners),
                deepCopyBindings(serviceBindings));
        Map<String, FileParseOutcome> reparsed = new TreeMap<>();
        for (String file : changedFiles) {
            FileParseOutcome outcome = files.get(file);
            if (outcome != null) {
                reparsed.put(file, outcome);
            }
        }
        if (previous == null) {
            reparsed.putAll(files);
        }
        return new ResolutionResult(snapshot, affected, Map.copyOf(reparsed), false);
    }

    private static void addParseDiagnostics(Map<String, FileParseOutcome> files,
                                            List<Diagnostic> globalDiagnostics) {
        for (FileParseOutcome outcome : files.values()) {
            globalDiagnostics.addAll(outcome.diagnostics());
        }
    }

    private static void detectDuplicateModuleNames(Map<String, FileParseOutcome> files,
                                                   Map<String, ModuleDeclaration> declarations,
                                                   List<Diagnostic> globalDiagnostics) {
        Map<String, List<FileParseOutcome>> byModule = new TreeMap<>();
        for (FileParseOutcome outcome : files.values()) {
            if (outcome.success()) {
                byModule.computeIfAbsent(outcome.declaration().name(),
                        key -> new ArrayList<>()).add(outcome);
            }
        }
        for (Map.Entry<String, List<FileParseOutcome>> entry : byModule.entrySet()) {
            List<FileParseOutcome> contenders = entry.getValue();
            if (contenders.size() > 1) {
                contenders.sort((a, b) -> a.file().compareTo(b.file()));
                FileParseOutcome winner = contenders.get(0);
                declarations.put(entry.getKey(), winner.declaration());
                for (int i = 1; i < contenders.size(); i++) {
                    FileParseOutcome loser = contenders.get(i);
                    globalDiagnostics.add(new Diagnostic(Diagnostic.Severity.ERROR,
                            "DUPLICATE_MODULE_NAME",
                            "module '" + entry.getKey() + "' is also declared in "
                                    + winner.file() + "; that declaration wins by file name order",
                            new Position(loser.file(), 1, 1)));
                }
            } else {
                declarations.put(entry.getKey(), contenders.get(0).declaration());
            }
        }
    }

    private static DependencyGraph buildGraph(Map<String, ModuleDeclaration> declarations,
                                              List<Diagnostic> globalDiagnostics) {
        Map<String, List<String>> edges = new TreeMap<>();
        Set<String> existing = declarations.keySet();
        for (ModuleDeclaration declaration : declarations.values()) {
            List<String> targets = new ArrayList<>();
            for (ModuleDependency dependency : declaration.requires()) {
                if (!existing.contains(dependency.name())) {
                    Diagnostic.Severity severity = dependency.optional()
                            ? Diagnostic.Severity.WARNING : Diagnostic.Severity.ERROR;
                    String code = dependency.optional()
                            ? "OPTIONAL_DEPENDENCY_MISSING" : "REQUIRED_DEPENDENCY_MISSING";
                    globalDiagnostics.add(new Diagnostic(severity, code,
                            "module '" + declaration.name() + "' "
                                    + (dependency.optional() ? "optionally " : "")
                                    + "requires '" + dependency.name()
                                    + "' which has no resolvable declaration",
                            dependency.position()));
                }
                if (dependency.name().equals(declaration.name())) {
                    globalDiagnostics.add(new Diagnostic(Diagnostic.Severity.WARNING,
                            "SELF_REQUIRES",
                            "module '" + declaration.name() + "' requires itself",
                            dependency.position()));
                }
                targets.add(dependency.name());
            }
            edges.put(declaration.name(), List.copyOf(new TreeSet<>(targets)));
        }
        return DependencyGraph.from(edges);
    }

    private static Map<String, ModuleResolution> buildModuleResolutions(
            Map<String, ModuleDeclaration> declarations,
            DependencyGraph graph,
            Map<String, Set<List<String>>> cyclesByModule,
            Map<String, PackageOwnership> packageOwners,
            Map<String, List<ServiceBinding>> serviceBindings) {
        Map<String, ModuleResolution> result = new TreeMap<>();
        for (ModuleDeclaration declaration : declarations.values()) {
            String name = declaration.name();
            Set<List<String>> cycles = cyclesByModule.getOrDefault(name, Set.of());
            List<List<String>> sortedCycles = new ArrayList<>(cycles);
            sortedCycles.sort((a, b) -> a.toString().compareTo(b.toString()));

            List<Diagnostic> diagnostics = new ArrayList<>();
            for (PackageOwnership ownership : packageOwners.values()) {
                if (name.equals(shadowingModule(ownership))) {
                    diagnostics.addAll(ownership.diagnostics());
                }
            }
            for (ServiceBinding binding : serviceBindings.getOrDefault(name, List.of())) {
                diagnostics.addAll(binding.diagnostics());
            }

            result.put(name, new ModuleResolution(
                    name,
                    declaration.file(),
                    List.copyOf(graph.directDependencies(name)),
                    List.copyOf(graph.transitiveDependencies(name)),
                    !cycles.isEmpty(),
                    List.copyOf(sortedCycles),
                    List.copyOf(diagnostics)));
        }
        return result;
    }

    private static String shadowingModule(PackageOwnership ownership) {
        if (!"PARENT_WINS".equals(ownership.rule()) || ownership.diagnostics().isEmpty()) {
            return null;
        }
        for (String contender : ownership.contenders()) {
            if (!contender.equals(ownership.owner())) {
                return contender;
            }
        }
        return null;
    }

    private static void addPackageDiagnostics(Map<String, PackageOwnership> packageOwners,
                                              List<Diagnostic> globalDiagnostics) {
        for (PackageOwnership ownership : packageOwners.values()) {
            globalDiagnostics.addAll(ownership.diagnostics());
        }
    }

    private static void addServiceDiagnostics(Map<String, List<ServiceBinding>> serviceBindings,
                                              List<Diagnostic> globalDiagnostics) {
        for (List<ServiceBinding> bindings : serviceBindings.values()) {
            for (ServiceBinding binding : bindings) {
                binding.diagnostics().stream()
                        .filter(diagnostic -> diagnostic.severity() != Diagnostic.Severity.INFO)
                        .forEach(globalDiagnostics::add);
            }
        }
    }

    private static void addCycleDiagnostics(List<List<String>> cycles,
                                            List<Diagnostic> globalDiagnostics) {
        for (List<String> cycle : cycles) {
            globalDiagnostics.add(new Diagnostic(Diagnostic.Severity.ERROR, "CYCLE",
                    "circular dependency detected: " + String.join(" -> ", cycle),
                    new Position("", 0, 0)));
        }
    }

    private static Set<String> computeAffected(
            ResolutionSnapshot previous,
            Set<String> changedFiles,
            Map<String, FileParseOutcome> currentFiles,
            Map<String, ModuleDeclaration> currentDeclarations,
            Map<String, PackageOwnership> currentOwners,
            Map<String, List<ServiceBinding>> currentBindings) {
        Set<String> affected = new TreeSet<>();
        for (String file : changedFiles) {
            addModuleOfFile(previous.files().get(file), affected);
            addModuleOfFile(currentFiles.get(file), affected);
        }
        Set<String> initial = Set.copyOf(affected);
        for (String module : currentDeclarations.keySet()) {
            for (String seed : initial) {
                if (dependsOn(currentDeclarations, module, seed)) {
                    affected.add(module);
                }
            }
        }
        for (PackageOwnership ownership : currentOwners.values()) {
            if (ownership.conflict()) {
                affected.addAll(ownership.contenders());
            }
        }
        for (Map.Entry<String, List<ServiceBinding>> entry : currentBindings.entrySet()) {
            for (ServiceBinding binding : entry.getValue()) {
                if (!"BOUND".equals(binding.status())
                        || initial.contains(binding.selectedProvider())) {
                    affected.add(entry.getKey());
                }
            }
        }
        return Set.copyOf(affected);
    }

    private static void addModuleOfFile(FileParseOutcome outcome, Set<String> target) {
        if (outcome != null && outcome.success()) {
            target.add(outcome.declaration().name());
        }
    }

    private static boolean dependsOn(Map<String, ModuleDeclaration> declarations,
                                     String module, String dependency) {
        if (module.equals(dependency)) {
            return true;
        }
        Set<String> seen = new HashSet<>();
        List<String> queue = new ArrayList<>(declarations.get(module).requires().stream()
                .map(ModuleDependency::name).toList());
        while (!queue.isEmpty()) {
            String current = queue.remove(0);
            if (current.equals(dependency)) {
                return true;
            }
            if (seen.add(current) && declarations.containsKey(current)) {
                queue.addAll(declarations.get(current).requires().stream()
                        .map(ModuleDependency::name).toList());
            }
        }
        return false;
    }

    private static Map<String, List<ServiceBinding>> deepCopyBindings(
            Map<String, List<ServiceBinding>> bindings) {
        Map<String, List<ServiceBinding>> copy = new LinkedHashMap<>();
        bindings.forEach((key, value) -> copy.put(key, List.copyOf(value)));
        return Map.copyOf(copy);
    }
}

package com.github.guoyyit.moduleresolverjava.engine;

import com.github.guoyyit.moduleresolverjava.analysis.PackageConflictResolver;
import com.github.guoyyit.moduleresolverjava.analysis.ServiceBinder;
import com.github.guoyyit.moduleresolverjava.graph.CycleDetector;
import com.github.guoyyit.moduleresolverjava.graph.DependencyGraph;
import com.github.guoyyit.moduleresolverjava.graph.Readability;
import com.github.guoyyit.moduleresolverjava.model.Diagnostic;
import com.github.guoyyit.moduleresolverjava.model.ExportedPackage;
import com.github.guoyyit.moduleresolverjava.model.FileParseOutcome;
import com.github.guoyyit.moduleresolverjava.model.ModuleDeclaration;
import com.github.guoyyit.moduleresolverjava.model.ModuleDependency;
import com.github.guoyyit.moduleresolverjava.model.ModuleResolution;
import com.github.guoyyit.moduleresolverjava.model.PackageOwnership;
import com.github.guoyyit.moduleresolverjava.model.Position;
import com.github.guoyyit.moduleresolverjava.model.ResolutionSnapshot;
import com.github.guoyyit.moduleresolverjava.model.ServiceBinding;
import com.github.guoyyit.moduleresolverjava.model.ServiceConsumption;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
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

        // Modules whose conclusions may change: the modules of the changed
        // files (old and new names) plus every module that can reach them
        // through requires edges. Everything outside this set is provably
        // unchanged and keeps the previous conclusion objects.
        Set<String> recompute = previous == null
                ? declarations.keySet()
                : computeRecomputeSet(previous, changedFiles, files, graph);

        Readability.Tables readability = previous == null
                ? Readability.computeAll(declarations)
                : Readability.computeIncremental(declarations, recompute,
                        previousReadable(previous), previousPropagated(previous));
        Map<String, List<String>> readable = readability.readable();
        Map<String, List<String>> propagated = readability.propagated();
        if (Thread.currentThread().isInterrupted()) {
            return new ResolutionResult(null, Set.of(), Map.of(), true);
        }

        PackageConflictResolver.OwnershipOutcome ownership =
                packageConflictResolver.resolveIncremental(declarations, graph,
                        previous == null ? Map.of() : previous.packageOwners(), recompute);
        Map<String, PackageOwnership> packageOwners = ownership.owners();
        if (Thread.currentThread().isInterrupted()) {
            return new ResolutionResult(null, Set.of(), Map.of(), true);
        }
        Map<String, List<ServiceBinding>> serviceBindings = serviceBinder.bindIncremental(
                declarations, graph, packageOwners, readable,
                previous == null ? Map.of() : previous.serviceBindings(),
                recompute, ownership.changedPackages());
        if (Thread.currentThread().isInterrupted()) {
            return new ResolutionResult(null, Set.of(), Map.of(), true);
        }

        List<List<String>> cycles = cycleDetector.findCycles(graph);
        Map<String, Set<List<String>>> cyclesByModule = cycleDetector.cyclesByModule(graph);

        ModulesOutcome modules = buildModuleResolutions(
                declarations, graph, cyclesByModule, packageOwners, serviceBindings,
                readable, propagated, previous, recompute, ownership.changedPackages());

        addPackageDiagnostics(packageOwners, globalDiagnostics);
        addServiceDiagnostics(serviceBindings, globalDiagnostics);
        addCycleDiagnostics(cycles, globalDiagnostics);

        Set<String> affected;
        if (previous == null) {
            affected = Set.copyOf(declarations.keySet());
        } else {
            // A consumer whose binding list object changed is affected even
            // when its module-level conclusion stayed identical.
            Set<String> changed = new TreeSet<>(modules.affected());
            for (Map.Entry<String, List<ServiceBinding>> entry : serviceBindings.entrySet()) {
                if (entry.getValue() != previous.serviceBindings().get(entry.getKey())) {
                    changed.add(entry.getKey());
                }
            }
            for (String consumer : previous.serviceBindings().keySet()) {
                if (!serviceBindings.containsKey(consumer)) {
                    changed.add(consumer);
                }
            }
            affected = Set.copyOf(changed);
        }

        ResolutionSnapshot snapshot = new ResolutionSnapshot(
                baseVersion + 1, Instant.now(),
                Map.copyOf(files),
                Map.copyOf(declarations),
                Map.copyOf(modules.resolutions()),
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

    /**
     * Result of the per-module conclusion pass: the conclusions of all
     * modules plus the set of modules whose conclusion actually changed.
     */
    private record ModulesOutcome(Map<String, ModuleResolution> resolutions,
                                  Set<String> affected) {
    }

    /**
     * Builds module conclusions, reusing the previous version's
     * {@link ModuleResolution} object for every module whose inputs cannot
     * have changed (outside the recompute set and untouched by ownership
     * changes) or whose freshly computed conclusion is content-equal. The
     * returned affected set contains exactly the modules that did not keep
     * the previous object.
     */
    private static ModulesOutcome buildModuleResolutions(
            Map<String, ModuleDeclaration> declarations,
            DependencyGraph graph,
            Map<String, Set<List<String>>> cyclesByModule,
            Map<String, PackageOwnership> packageOwners,
            Map<String, List<ServiceBinding>> serviceBindings,
            Map<String, List<String>> readable,
            Map<String, List<String>> propagated,
            ResolutionSnapshot previous,
            Set<String> recompute,
            Set<String> changedOwnershipPackages) {
        Set<String> exportsChanged = new HashSet<>();
        Set<String> consumesChanged = new HashSet<>();
        if (!changedOwnershipPackages.isEmpty()) {
            for (ModuleDeclaration declaration : declarations.values()) {
                for (ExportedPackage exported : declaration.exports()) {
                    if (changedOwnershipPackages.contains(exported.packageName())) {
                        exportsChanged.add(declaration.name());
                    }
                }
                for (ServiceConsumption consumption : declaration.consumes()) {
                    String pkg = packageOf(consumption.serviceInterface());
                    if (pkg != null && changedOwnershipPackages.contains(pkg)) {
                        consumesChanged.add(declaration.name());
                    }
                }
            }
        }

        Map<String, ModuleResolution> result = new TreeMap<>();
        Set<String> affected = new TreeSet<>();
        for (ModuleDeclaration declaration : declarations.values()) {
            String name = declaration.name();
            ModuleResolution previousResolution =
                    previous == null ? null : previous.modules().get(name);
            boolean mustRecompute = previousResolution == null
                    || recompute.contains(name)
                    || exportsChanged.contains(name)
                    || consumesChanged.contains(name);
            if (!mustRecompute) {
                result.put(name, previousResolution);
                continue;
            }
            ModuleResolution fresh = buildOneModuleResolution(declaration, graph,
                    cyclesByModule, packageOwners, serviceBindings, readable, propagated);
            if (fresh.equals(previousResolution)) {
                result.put(name, previousResolution);
            } else {
                result.put(name, fresh);
                affected.add(name);
            }
        }
        return new ModulesOutcome(result, affected);
    }

    private static ModuleResolution buildOneModuleResolution(
            ModuleDeclaration declaration,
            DependencyGraph graph,
            Map<String, Set<List<String>>> cyclesByModule,
            Map<String, PackageOwnership> packageOwners,
            Map<String, List<ServiceBinding>> serviceBindings,
            Map<String, List<String>> readable,
            Map<String, List<String>> propagated) {
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

        return new ModuleResolution(
                name,
                declaration.file(),
                List.copyOf(graph.directDependencies(name)),
                List.copyOf(graph.transitiveDependencies(name)),
                readable.getOrDefault(name, List.of()),
                propagated.getOrDefault(name, List.of()),
                !cycles.isEmpty(),
                List.copyOf(sortedCycles),
                List.copyOf(diagnostics));
    }

    private static String packageOf(String className) {
        int dot = className.lastIndexOf('.');
        return dot < 0 ? null : className.substring(0, dot);
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

    /**
     * Computes the set of modules that may observe a change: the modules
     * declared by the changed files (both the previous and the current
     * declaration, covering renames, deletions and parse failures) plus every
     * module that can reach one of those seeds through requires edges in the
     * current graph. Dependency edges only change when the declaring file
     * changes, so a module outside this set has its whole forward dependency
     * cone unchanged and none of its conclusions can differ.
     */
    private static Set<String> computeRecomputeSet(ResolutionSnapshot previous,
                                                   Set<String> changedFiles,
                                                   Map<String, FileParseOutcome> currentFiles,
                                                   DependencyGraph graph) {
        Set<String> seeds = new TreeSet<>();
        for (String file : changedFiles) {
            addModuleOfFile(previous.files().get(file), seeds);
            addModuleOfFile(currentFiles.get(file), seeds);
        }
        Map<String, List<String>> reverse = new HashMap<>();
        for (String module : graph.modules()) {
            for (String target : graph.directDependencies(module)) {
                reverse.computeIfAbsent(target, key -> new ArrayList<>()).add(module);
            }
        }
        Set<String> recompute = new TreeSet<>(seeds);
        ArrayDeque<String> queue = new ArrayDeque<>(seeds);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            for (String parent : reverse.getOrDefault(current, List.of())) {
                if (recompute.add(parent)) {
                    queue.add(parent);
                }
            }
        }
        return recompute;
    }

    private static Map<String, List<String>> previousReadable(ResolutionSnapshot previous) {
        Map<String, List<String>> map = new HashMap<>();
        previous.modules().forEach((name, resolution) ->
                map.put(name, resolution.readableModules()));
        return map;
    }

    private static Map<String, List<String>> previousPropagated(ResolutionSnapshot previous) {
        Map<String, List<String>> map = new HashMap<>();
        previous.modules().forEach((name, resolution) ->
                map.put(name, resolution.propagatedModules()));
        return map;
    }

    private static void addModuleOfFile(FileParseOutcome outcome, Set<String> target) {
        if (outcome != null && outcome.success()) {
            target.add(outcome.declaration().name());
        }
    }

    private static Map<String, List<ServiceBinding>> deepCopyBindings(
            Map<String, List<ServiceBinding>> bindings) {
        Map<String, List<ServiceBinding>> copy = new LinkedHashMap<>();
        bindings.forEach((key, value) -> copy.put(key, List.copyOf(value)));
        return Map.copyOf(copy);
    }
}

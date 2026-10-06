package com.github.guoyyit.moduleresolverjava.engine;

import com.github.guoyyit.moduleresolverjava.analysis.PackageConflictResolver;
import com.github.guoyyit.moduleresolverjava.analysis.ServiceBinder;
import com.github.guoyyit.moduleresolverjava.analysis.VisibilityIndex;
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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;

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

    /**
     * Cache of the visibility index belonging to the most recently produced
     * snapshot, keyed by the identity of that snapshot's declarations map.
     * Purely a speed-up: the index content is a pure function of the
     * declarations, so a miss simply triggers a full (deterministic)
     * recomputation. Bounded to one entry, so memory does not grow with the
     * number of commits.
     */
    private final AtomicReference<CachedIndex> indexCache = new AtomicReference<>();

    private record CachedIndex(Map<String, ModuleDeclaration> declarations,
                               VisibilityIndex index) {
    }

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

        Set<String> changedModules = changedModuleNames(previous, changedFiles, files);
        VisibilityIndex visibilityIndex =
                visibilityIndex(previous, declarations, changedModules);
        if (Thread.currentThread().isInterrupted()) {
            return new ResolutionResult(null, Set.of(), Map.of(), true);
        }

        Map<String, PackageOwnership> packageOwners =
                packageConflictResolver.resolve(declarations);
        if (Thread.currentThread().isInterrupted()) {
            return new ResolutionResult(null, Set.of(), Map.of(), true);
        }
        Map<String, List<ServiceBinding>> serviceBindings =
                serviceBinder.bind(declarations, graph, packageOwners, visibilityIndex);
        if (Thread.currentThread().isInterrupted()) {
            return new ResolutionResult(null, Set.of(), Map.of(), true);
        }

        List<List<String>> cycles = cycleDetector.findCycles(graph);
        Map<String, Set<List<String>>> cyclesByModule = cycleDetector.cyclesByModule(graph);

        Set<String> affected = previous == null
                ? Set.copyOf(declarations.keySet())
                : computeAffected(previous, changedFiles, files, declarations,
                        packageOwners, serviceBindings);

        Map<String, ModuleResolution> modules = buildModuleResolutions(
                declarations, graph, cyclesByModule, packageOwners, serviceBindings,
                visibilityIndex, previous);

        addPackageDiagnostics(packageOwners, globalDiagnostics);
        addServiceDiagnostics(serviceBindings, globalDiagnostics);
        addCycleDiagnostics(cycles, globalDiagnostics);

        ResolutionSnapshot snapshot = new ResolutionSnapshot(
                baseVersion + 1, Instant.now(),
                Map.copyOf(files),
                Map.copyOf(declarations),
                Map.copyOf(modules),
                List.copyOf(globalDiagnostics),
                Map.copyOf(packageOwners),
                deepCopyBindings(serviceBindings));
        indexCache.set(new CachedIndex(snapshot.declarations(), visibilityIndex));
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
     * Builds the per-module conclusions. Visibility lists are taken from the
     * {@link VisibilityIndex} (shared immutable objects, no copies). When the
     * freshly computed conclusion is content-equal to the previous version's,
     * the previous object is reused as-is, so modules whose conclusion did not
     * truly change keep the exact same conclusion object across versions.
     */
    private static Map<String, ModuleResolution> buildModuleResolutions(
            Map<String, ModuleDeclaration> declarations,
            DependencyGraph graph,
            Map<String, Set<List<String>>> cyclesByModule,
            Map<String, PackageOwnership> packageOwners,
            Map<String, List<ServiceBinding>> serviceBindings,
            VisibilityIndex visibilityIndex,
            ResolutionSnapshot previous) {
        Map<String, List<Diagnostic>> ownershipDiagnostics = new TreeMap<>();
        for (PackageOwnership ownership : packageOwners.values()) {
            String shadowed = shadowingModule(ownership);
            if (shadowed != null) {
                ownershipDiagnostics.computeIfAbsent(shadowed,
                        key -> new ArrayList<>()).addAll(ownership.diagnostics());
            }
        }
        Map<String, ModuleResolution> result = new TreeMap<>();
        for (ModuleDeclaration declaration : declarations.values()) {
            String name = declaration.name();
            Set<List<String>> cycles = cyclesByModule.getOrDefault(name, Set.of());
            List<List<String>> sortedCycles = new ArrayList<>(cycles);
            sortedCycles.sort((a, b) -> a.toString().compareTo(b.toString()));

            List<Diagnostic> diagnostics = new ArrayList<>();
            diagnostics.addAll(ownershipDiagnostics.getOrDefault(name, List.of()));
            for (ServiceBinding binding : serviceBindings.getOrDefault(name, List.of())) {
                diagnostics.addAll(binding.diagnostics());
            }

            VisibilityIndex.ModuleVisibility visibility = visibilityIndex.visibilityOf(name);
            ModuleResolution candidate = new ModuleResolution(
                    name,
                    declaration.file(),
                    List.copyOf(graph.directDependencies(name)),
                    visibility.transitiveDependencies(),
                    visibility.readableModules(),
                    !cycles.isEmpty(),
                    List.copyOf(sortedCycles),
                    List.copyOf(diagnostics));
            ModuleResolution previousResolution =
                    previous == null ? null : previous.modules().get(name);
            result.put(name, candidate.equals(previousResolution)
                    ? previousResolution : candidate);
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

    /**
     * Modules whose conclusions may change as a result of the changed files:
     * <ol>
     *   <li>the modules declared by the changed files (old and new names);</li>
     *   <li>their reverse dependency cone — everything that can reach them
     *       through requires edges in the old or the new graph, which covers
     *       readability changes caused by {@code requires transitive}
     *       modifier edits;</li>
     *   <li>all contenders of any package whose ownership verdict changed
     *       (covers conflicts that appeared or disappeared);</li>
     *   <li>all consumers whose service bindings changed.</li>
     * </ol>
     */
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
        Map<String, Set<String>> reverse = new TreeMap<>();
        addReverseEdges(previous.declarations(), reverse);
        addReverseEdges(currentDeclarations, reverse);
        Deque<String> queue = new ArrayDeque<>(affected);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            for (String source : reverse.getOrDefault(current, Set.of())) {
                if (affected.add(source)) {
                    queue.add(source);
                }
            }
        }
        Set<String> packages = new TreeSet<>(previous.packageOwners().keySet());
        packages.addAll(currentOwners.keySet());
        for (String packageName : packages) {
            PackageOwnership before = previous.packageOwners().get(packageName);
            PackageOwnership after = currentOwners.get(packageName);
            if (!Objects.equals(before, after)) {
                if (before != null) {
                    affected.addAll(before.contenders());
                }
                if (after != null) {
                    affected.addAll(after.contenders());
                }
            }
        }
        Set<String> consumers = new TreeSet<>(previous.serviceBindings().keySet());
        consumers.addAll(currentBindings.keySet());
        for (String consumer : consumers) {
            if (!Objects.equals(previous.serviceBindings().get(consumer),
                    currentBindings.get(consumer))) {
                affected.add(consumer);
            }
        }
        return Set.copyOf(affected);
    }

    private static void addReverseEdges(Map<String, ModuleDeclaration> declarations,
                                        Map<String, Set<String>> reverse) {
        for (ModuleDeclaration declaration : declarations.values()) {
            for (ModuleDependency dependency : declaration.requires()) {
                reverse.computeIfAbsent(dependency.name(),
                        key -> new TreeSet<>()).add(declaration.name());
            }
        }
    }

    /**
     * Old and new module names declared by the changed files.
     */
    private static Set<String> changedModuleNames(ResolutionSnapshot previous,
                                                  Set<String> changedFiles,
                                                  Map<String, FileParseOutcome> currentFiles) {
        Set<String> names = new TreeSet<>();
        if (previous != null) {
            for (String file : changedFiles) {
                addModuleOfFile(previous.files().get(file), names);
                addModuleOfFile(currentFiles.get(file), names);
            }
        }
        return names;
    }

    /**
     * Returns the visibility index for the current declarations, advancing the
     * cached previous index incrementally when it is available.
     */
    private VisibilityIndex visibilityIndex(ResolutionSnapshot previous,
                                            Map<String, ModuleDeclaration> declarations,
                                            Set<String> changedModules) {
        if (previous == null) {
            return VisibilityIndex.compute(declarations);
        }
        CachedIndex cached = indexCache.get();
        if (cached != null && cached.declarations() == previous.declarations()) {
            return cached.index().update(
                    previous.declarations(), declarations, changedModules);
        }
        return VisibilityIndex.compute(declarations);
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

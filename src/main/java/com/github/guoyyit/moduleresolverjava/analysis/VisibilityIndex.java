package com.github.guoyyit.moduleresolverjava.analysis;

import com.github.guoyyit.moduleresolverjava.model.ModuleDeclaration;
import com.github.guoyyit.moduleresolverjava.model.ModuleDependency;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Incrementally maintained per-module visibility conclusions.
 *
 * <p>For every module the index tracks three sorted, deterministic views:
 * <ul>
 *   <li>{@code transitiveDependencies}: modules reachable through any
 *       {@code requires} chain, excluding direct requirements;</li>
 *   <li>{@code readableModules}: modules whose exported packages are visible
 *       — direct requirements plus the closure over
 *       {@code requires transitive} edges;</li>
 *   <li>{@code propagation} (internal): the module itself plus everything it
 *       re-exports upstream through {@code requires transitive} edges; used
 *       to compose readable sets during incremental updates.</li>
 * </ul>
 *
 * <p>Instances are immutable. {@link #update} invalidates exactly the reverse
 * dependency cone of the changed modules (computed over the union of the old
 * and new edge sets, so removed edges are honoured), recomputes only that
 * cone, and shares every untouched per-module view with the previous index.
 * Incremental cost and memory therefore stay proportional to the affected
 * cone instead of the whole module set, and repeated commits do not
 * accumulate garbage: views that survive a commit are the same objects.
 */
public final class VisibilityIndex {

    /**
     * Public per-module visibility conclusion (sorted lists).
     */
    public record ModuleVisibility(List<String> transitiveDependencies,
                                   List<String> readableModules) {
        static final ModuleVisibility EMPTY = new ModuleVisibility(List.of(), List.of());
    }

    private final Map<String, ModuleVisibility> visibility;
    /** Module -&gt; itself plus its {@code requires transitive} closure. */
    private final Map<String, List<String>> propagation;

    private VisibilityIndex(Map<String, ModuleVisibility> visibility,
                            Map<String, List<String>> propagation) {
        this.visibility = visibility;
        this.propagation = propagation;
    }

    /**
     * Computes a full index from scratch. Deterministic for the same input.
     */
    public static VisibilityIndex compute(Map<String, ModuleDeclaration> declarations) {
        Map<String, ModuleDeclaration> safe = declarations == null
                ? Map.of() : declarations;
        return new VisibilityIndex(Map.of(), Map.of())
                .update(Map.of(), safe, Set.copyOf(safe.keySet()));
    }

    /**
     * Incrementally recomputes only the modules whose visibility can change
     * given the changed module names; reuses all other conclusion objects.
     */
    public VisibilityIndex update(Map<String, ModuleDeclaration> previousDeclarations,
                                  Map<String, ModuleDeclaration> currentDeclarations,
                                  Set<String> changedModules) {
        Map<String, ModuleDeclaration> current = new TreeMap<>(currentDeclarations);
        Adjacency adjacency = Adjacency.of(current);
        Set<String> invalidated = invalidatedCone(previousDeclarations, current, changedModules);

        Map<String, List<String>> newPropagation = new TreeMap<>();
        Map<String, ModuleVisibility> newVisibility = new TreeMap<>();
        // Pass 1: propagation closures (readable sets of downstream modules
        // are composed from these, so they must all be final first).
        for (String module : current.keySet()) {
            List<String> cachedPropagation = propagation.get(module);
            if (!invalidated.contains(module) && cachedPropagation != null) {
                newPropagation.put(module, cachedPropagation);
            } else {
                newPropagation.put(module, List.copyOf(propagationClosure(
                        module, adjacency.transitiveEdges, invalidated)));
            }
        }
        // Pass 2: transitive dependencies and readable modules.
        for (String module : current.keySet()) {
            ModuleVisibility cachedVisibility = visibility.get(module);
            if (!invalidated.contains(module) && cachedVisibility != null) {
                newVisibility.put(module, cachedVisibility);
                continue;
            }
            Set<String> reachableAll = reachableClosure(
                    module, adjacency.allEdges, invalidated);
            Set<String> direct = adjacency.targetsOf(module, adjacency.allEdges);
            Set<String> transitive = new TreeSet<>(reachableAll);
            transitive.removeAll(direct);
            transitive.remove(module);
            Set<String> readable = new TreeSet<>();
            for (String dependency : adjacency.allEdges.getOrDefault(module, List.of())) {
                List<String> propagated = newPropagation.get(dependency);
                if (propagated != null) {
                    readable.addAll(propagated);
                } else {
                    // Dependency without a declaration: readable as a leaf only.
                    readable.add(dependency);
                }
            }
            newVisibility.put(module, new ModuleVisibility(
                    List.copyOf(transitive), List.copyOf(readable)));
        }
        return new VisibilityIndex(
                java.util.Collections.unmodifiableMap(newVisibility),
                java.util.Collections.unmodifiableMap(newPropagation));
    }

    /**
     * Visibility conclusion for one module; empty views for unknown modules.
     */
    public ModuleVisibility visibilityOf(String module) {
        return visibility.getOrDefault(module, ModuleVisibility.EMPTY);
    }

    /**
     * Modules whose visibility may change: the changed modules plus everything
     * that can reach them through requires edges in the old or new graph.
     */
    private static Set<String> invalidatedCone(
            Map<String, ModuleDeclaration> previousDeclarations,
            Map<String, ModuleDeclaration> currentDeclarations,
            Set<String> changedModules) {
        Map<String, Set<String>> reverse = new TreeMap<>();
        addReverseEdges(previousDeclarations, reverse);
        addReverseEdges(currentDeclarations, reverse);
        Set<String> invalidated = new TreeSet<>(changedModules);
        Deque<String> queue = new ArrayDeque<>(changedModules);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            for (String source : reverse.getOrDefault(current, Set.of())) {
                if (invalidated.add(source)) {
                    queue.add(source);
                }
            }
        }
        return invalidated;
    }

    private static void addReverseEdges(Map<String, ModuleDeclaration> declarations,
                                        Map<String, Set<String>> reverse) {
        if (declarations == null) {
            return;
        }
        for (ModuleDeclaration declaration : declarations.values()) {
            for (ModuleDependency dependency : declaration.requires()) {
                reverse.computeIfAbsent(dependency.name(),
                        key -> new TreeSet<>()).add(declaration.name());
            }
        }
    }

    /**
     * Closure over {@code requires transitive} edges starting at (and
     * including) {@code start}; reuses cached propagation sets of modules
     * outside the invalidated cone instead of re-traversing them.
     */
    private Set<String> propagationClosure(String start,
                                           Map<String, List<String>> transitiveEdges,
                                           Set<String> invalidated) {
        Set<String> result = new TreeSet<>();
        Deque<String> queue = new ArrayDeque<>();
        result.add(start);
        queue.add(start);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            for (String next : transitiveEdges.getOrDefault(current, List.of())) {
                List<String> cached = propagation.get(next);
                if (cached != null && !invalidated.contains(next)) {
                    result.addAll(cached);
                } else if (result.add(next)) {
                    queue.add(next);
                }
            }
        }
        return result;
    }

    /**
     * Closure over all requires edges starting at (and including)
     * {@code start}; reuses cached conclusions of modules outside the
     * invalidated cone instead of re-traversing them.
     */
    private Set<String> reachableClosure(String start,
                                         Map<String, List<String>> allEdges,
                                         Set<String> invalidated) {
        Set<String> result = new TreeSet<>();
        Deque<String> queue = new ArrayDeque<>();
        result.add(start);
        queue.add(start);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            for (String next : allEdges.getOrDefault(current, List.of())) {
                ModuleVisibility cached = visibility.get(next);
                if (cached != null && !invalidated.contains(next)) {
                    // reachable(next) == {next} + direct(next) + transitive(next)
                    result.add(next);
                    result.addAll(allEdges.getOrDefault(next, List.of()));
                    result.addAll(cached.transitiveDependencies());
                } else if (result.add(next)) {
                    queue.add(next);
                }
            }
        }
        return result;
    }

    /**
     * Sorted adjacency views of the requires edges of all declarations.
     */
    private record Adjacency(Map<String, List<String>> allEdges,
                             Map<String, List<String>> transitiveEdges) {

        static Adjacency of(Map<String, ModuleDeclaration> declarations) {
            Map<String, List<String>> all = new TreeMap<>();
            Map<String, List<String>> transitive = new TreeMap<>();
            for (ModuleDeclaration declaration : declarations.values()) {
                Set<String> allTargets = new TreeSet<>();
                Set<String> transitiveTargets = new TreeSet<>();
                for (ModuleDependency dependency : declaration.requires()) {
                    allTargets.add(dependency.name());
                    if (dependency.transitive()) {
                        transitiveTargets.add(dependency.name());
                    }
                }
                all.put(declaration.name(), List.copyOf(allTargets));
                transitive.put(declaration.name(), List.copyOf(transitiveTargets));
            }
            return new Adjacency(all, transitive);
        }

        Set<String> targetsOf(String module, Map<String, List<String>> edges) {
            return new TreeSet<>(edges.getOrDefault(module, List.of()));
        }
    }
}

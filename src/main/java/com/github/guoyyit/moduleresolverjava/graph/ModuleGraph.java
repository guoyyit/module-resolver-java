package com.github.guoyyit.moduleresolverjava.graph;

import com.github.guoyyit.moduleresolverjava.model.ModuleDeclaration;
import com.github.guoyyit.moduleresolverjava.model.RequireDirective;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.LinkedHashSet;

/**
 * Immutable module dependency graph with export visibility information.
 *
 * <p>Direct edges come from {@code requires} directives. A module M reads
 * module N when M directly requires N, or when M requires (transitively or
 * not) some module P that {@code requires transitive} N.</p>
 */
public final class ModuleGraph {

    private final Map<String, ModuleDeclaration> declarations;
    private final Map<String, List<String>> direct;
    private final Map<String, Set<String>> dependents;
    private final Map<String, Set<String>> readable = new HashMap<>();
    private final Map<String, Set<String>> closure = new HashMap<>();

    public ModuleGraph(Map<String, ModuleDeclaration> declarations) {
        this.declarations = Map.copyOf(declarations);
        this.direct = new HashMap<>();
        this.dependents = new HashMap<>();
        for (ModuleDeclaration declaration : this.declarations.values()) {
            LinkedHashSet<String> targets = new LinkedHashSet<>();
            for (RequireDirective require : declaration.requires()) {
                targets.add(require.moduleName());
            }
            List<String> targetList = List.copyOf(targets);
            this.direct.put(declaration.name(), targetList);
            for (String target : targetList) {
                this.dependents.computeIfAbsent(target, key -> new TreeSet<>())
                        .add(declaration.name());
            }
        }
    }

    public Set<String> moduleNames() {
        return Collections.unmodifiableSet(declarations.keySet());
    }

    public ModuleDeclaration declaration(String module) {
        return declarations.get(module);
    }

    /**
     * Eagerly computes every closure/readability set in O(V+E). Called by
     * the engine before building all per-module results.
     */
    public void precompute() {
        precomputeAll();
    }

    /** Direct required module names (declared in the file, order preserved). */
    public List<String> directDependencies(String module) {
        return direct.getOrDefault(module, List.of());
    }

    /** Modules that directly {@code require} the given module. */
    public Set<String> directDependents(String module) {
        return Collections.unmodifiableSet(
                dependents.getOrDefault(module, Set.of()));
    }

    /** Transitive dependencies via plain {@code requires} (not readable). */
    public Set<String> transitiveDependencies(String module) {
        Set<String> all = closure(module);
        TreeSet<String> result = new TreeSet<>(all);
        result.remove(module);
        result.removeAll(directDependencies(module));
        return Collections.unmodifiableSortedSet(result);
    }

    /** Modules whose exported packages are visible to the given module. */
    public Set<String> readableModules(String module) {
        if (!declarations.containsKey(module)) {
            return Set.of();
        }
        return Collections.unmodifiableSet(readable(module));
    }

    /** Fully resolved dependency closure (direct + all transitive). */
    public Set<String> dependencyClosure(String module) {
        return Collections.unmodifiableSet(closure(module));
    }

    private Set<String> readable(String module) {
        synchronized (readable) {
            Set<String> cached = readable.get(module);
            if (cached == null) {
                precomputeAll();
                cached = readable.get(module);
            }
            return cached;
        }
    }

    private Set<String> closure(String module) {
        synchronized (closure) {
            Set<String> cached = closure.get(module);
            if (cached == null) {
                precomputeAll();
                cached = closure.get(module);
            }
            return cached;
        }
    }

    /**
     * Bulk O(V+E) pre-computation with memoized DFS. DFS naturally follows
     * the dependency order: when a node's successor is visited first, its
     * fully computed set is reused. Cycles are tolerated because every node
     * is inserted into its own set before recursing (it simply absorbs its
     * SCC-mates' final sets on return; the resulting set is still the
     * complete reachability closure).
     */
    private void precomputeAll() {
        if (!readable.isEmpty() && !closure.isEmpty()) {
            return;
        }
        Map<String, Set<String>> closureSets = new HashMap<>();
        for (String name : declarations.keySet()) {
            closureDfs(name, closureSets);
        }
        Map<String, Set<String>> readableSets = computeReadableAll();
        closureSets.replaceAll((key, value) ->
                Collections.unmodifiableSet(new TreeSet<>(value)));
        readableSets.replaceAll((key, value) ->
                Collections.unmodifiableSet(new TreeSet<>(value)));
        synchronized (closure) {
            if (closure.isEmpty()) {
                closure.putAll(closureSets);
            }
        }
        synchronized (readable) {
            if (readable.isEmpty()) {
                readable.putAll(readableSets);
            }
        }
    }

    private Set<String> closureDfs(String module, Map<String, Set<String>> memo) {
        Set<String> existing = memo.get(module);
        if (existing != null) {
            return existing;
        }
        Set<String> set = new HashSet<>();
        memo.put(module, set);
        set.add(module);
        List<String> next = new ArrayList<>(direct.getOrDefault(module, List.of()));
        Collections.sort(next);
        for (String target : next) {
            set.add(target);
            if (declarations.containsKey(target)) {
                set.addAll(closureDfs(target, memo));
            }
        }
        return set;
    }

    /**
     * Computes readability for every module in O(V+E) using SCC condensation
     * plus topological dynamic programming.
     *
     * <p>Two quantities per node:
     * <ul>
     *   <li>{@code T(X)} — nodes reachable from X using only
     *       {@code requires transitive} edges (including X);</li>
     *   <li>{@code R(M)} — nodes M reads: M itself, every direct
     *       dependency D, and {@code T(D)} for every direct dependency D
     *       (the first edge may be plain; every subsequent edge must be
     *       transitive).</li>
     * </ul>
     * T is a plain reachability closure computed once per SCC in reverse
     * topological order; R is then a direct assembly from T.</p>
     */
    private Map<String, Set<String>> computeReadableAll() {
        List<Set<String>> components = stronglyConnectedComponents();
        int componentCount = components.size();
        Map<String, Integer> componentOf = new HashMap<>();
        for (int index = 0; index < componentCount; index++) {
            for (String member : components.get(index)) {
                componentOf.put(member, index);
            }
        }

        List<Set<String>> transitiveNodeSuccessors =
                new ArrayList<>(componentCount);
        List<Set<String>> allDirectTargets = new ArrayList<>(componentCount);
        for (int index = 0; index < componentCount; index++) {
            transitiveNodeSuccessors.add(new HashSet<>());
            allDirectTargets.add(new HashSet<>());
        }
        for (ModuleDeclaration declaration : declarations.values()) {
            int source = componentOf.get(declaration.name());
            for (RequireDirective require : declaration.requires()) {
                allDirectTargets.get(source).add(require.moduleName());
                if (require.transitive()
                        && declarations.containsKey(require.moduleName())) {
                    transitiveNodeSuccessors.get(source)
                            .add(require.moduleName());
                }
            }
        }

        // T over the SCC DAG, using transitive edges only.
        List<Set<Integer>> transitiveComponentSuccessors =
                new ArrayList<>(componentCount);
        for (int index = 0; index < componentCount; index++) {
            Set<Integer> successors = new HashSet<>();
            for (String node : transitiveNodeSuccessors.get(index)) {
                successors.add(componentOf.get(node));
            }
            transitiveComponentSuccessors.add(successors);
        }
        List<Integer> order = reverseTopologicalOrder(
                componentCount, transitiveComponentSuccessors);
        Map<Integer, Set<String>> transitiveReachability = new HashMap<>();
        for (int component : order) {
            Set<String> reachable = new HashSet<>(components.get(component));
            for (int successor : transitiveComponentSuccessors.get(component)) {
                reachable.addAll(components.get(successor));
                reachable.addAll(transitiveReachability.get(successor));
            }
            transitiveReachability.put(component, reachable);
        }

        Map<String, Set<String>> result = new HashMap<>();
        for (int component = 0; component < componentCount; component++) {
            for (String member : components.get(component)) {
                Set<String> readable = new HashSet<>();
                readable.add(member);
                ModuleDeclaration declaration = declarations.get(member);
                for (RequireDirective require : declaration.requires()) {
                    readable.add(require.moduleName());
                    if (declarations.containsKey(require.moduleName())) {
                        readable.addAll(transitiveReachability.get(
                                componentOf.get(require.moduleName())));
                    }
                }
                result.put(member, Collections.unmodifiableSet(
                        new TreeSet<>(readable)));
            }
        }
        return result;
    }

    /** Tarjan SCC, returned in reverse-topological order (Tarjan natural). */
    private List<Set<String>> stronglyConnectedComponents() {
        Map<String, Integer> indices = new HashMap<>();
        Map<String, Integer> lowLinks = new HashMap<>();
        Deque<String> stack = new ArrayDeque<>();
        Set<String> onStack = new HashSet<>();
        int[] counter = {0};
        List<Set<String>> components = new ArrayList<>();
        List<String> names = new ArrayList<>(declarations.keySet());
        names.sort(String::compareTo);
        for (String name : names) {
            if (!indices.containsKey(name)) {
                strongConnect(name, indices, lowLinks, stack, onStack,
                        counter, components);
            }
        }
        return components;
    }

    private void strongConnect(String module,
                               Map<String, Integer> indices,
                               Map<String, Integer> lowLinks,
                               Deque<String> stack,
                               Set<String> onStack,
                               int[] counter,
                               List<Set<String>> components) {
        indices.put(module, counter[0]);
        lowLinks.put(module, counter[0]);
        counter[0]++;
        stack.push(module);
        onStack.add(module);
        List<String> successors =
                new ArrayList<>(direct.getOrDefault(module, List.of()));
        successors.sort(String::compareTo);
        for (String successor : successors) {
            if (!declarations.containsKey(successor)) {
                continue;
            }
            if (!indices.containsKey(successor)) {
                strongConnect(successor, indices, lowLinks, stack, onStack,
                        counter, components);
                lowLinks.put(module,
                        Math.min(lowLinks.get(module), lowLinks.get(successor)));
            } else if (onStack.contains(successor)) {
                lowLinks.put(module,
                        Math.min(lowLinks.get(module), indices.get(successor)));
            }
        }
        if (lowLinks.get(module).equals(indices.get(module))) {
            Set<String> component = new TreeSet<>();
            String member;
            do {
                member = stack.pop();
                onStack.remove(member);
                component.add(member);
            } while (!member.equals(module));
            components.add(component);
        }
    }

    /**
     * Orders components so successors precede predecessors (reverse
     * topological), enabling dynamic programming. Tarjan already emits in
     * that order, but DFS discovery here is alphabetical so we derive it
     * explicitly from the DAG to stay robust.
     */
    private List<Integer> reverseTopologicalOrder(
            int componentCount, List<Set<Integer>> successors) {
        List<Integer> order = new ArrayList<>();
        boolean[] visited = new boolean[componentCount];
        for (int component = 0; component < componentCount; component++) {
            reverseTopologicalVisit(component, successors, visited, order);
        }
        return order;
    }

    private void reverseTopologicalVisit(int component,
                                         List<Set<Integer>> successors,
                                         boolean[] visited,
                                         List<Integer> order) {
        if (visited[component]) {
            return;
        }
        visited[component] = true;
        List<Integer> sorted = new ArrayList<>(successors.get(component));
        sorted.sort(Integer::compareTo);
        for (int successor : sorted) {
            reverseTopologicalVisit(successor, successors, visited, order);
        }
        order.add(component);
    }

    private static List<RequireDirective> sortedRequires(ModuleDeclaration declaration) {
        List<RequireDirective> requires = new ArrayList<>(declaration.requires());
        requires.sort((a, b) -> a.moduleName().compareTo(b.moduleName()));
        return requires;
    }
}

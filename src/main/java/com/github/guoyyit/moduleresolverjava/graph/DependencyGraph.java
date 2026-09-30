package com.github.guoyyit.moduleresolverjava.graph;

import java.util.List;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.Set;
import java.util.LinkedHashSet;

/**
 * Immutable directed dependency graph with deterministic iteration order.
 */
public final class DependencyGraph {

    private final Map<String, List<String>> edges;

    private DependencyGraph(Map<String, List<String>> edges) {
        this.edges = edges;
    }

    public static DependencyGraph from(Map<String, List<String>> edges) {
        Map<String, List<String>> normalized = new TreeMap<>();
        if (edges != null) {
            Set<String> nodes = new TreeSet<>();
            nodes.addAll(edges.keySet());
            edges.values().forEach(list -> nodes.addAll(list));
            for (String node : nodes) {
                Set<String> targets = new TreeSet<>();
                List<String> raw = edges.get(node);
                if (raw != null) {
                    targets.addAll(raw);
                }
                normalized.put(node, List.copyOf(targets));
            }
        }
        return new DependencyGraph(Map.copyOf(normalized));
    }

    public Set<String> modules() {
        return edges.keySet();
    }

    public List<String> directDependencies(String module) {
        return edges.getOrDefault(module, List.of());
    }

    /**
     * Reachable dependencies that are not direct ones, in deterministic
     * breadth-first order sorted alphabetically at each level.
     */
    public List<String> transitiveDependencies(String module) {
        Set<String> visited = new LinkedHashSet<>(directDependencies(module));
        Set<String> direct = new LinkedHashSet<>(directDependencies(module));
        Deque<String> queue = new ArrayDeque<>(direct);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            for (String next : directDependencies(current)) {
                if (visited.add(next)) {
                    queue.add(next);
                }
            }
        }
        visited.remove(module);
        List<String> result = new java.util.ArrayList<>();
        for (String name : visited) {
            if (!direct.contains(name)) {
                result.add(name);
            }
        }
        return List.copyOf(result);
    }

    public boolean hasEdge(String from, String to) {
        return directDependencies(from).contains(to);
    }

    /**
     * Canonical adjacency representation; useful for snapshots and tests.
     */
    public Map<String, List<String>> adjacency() {
        return new LinkedHashMap<>(edges);
    }
}

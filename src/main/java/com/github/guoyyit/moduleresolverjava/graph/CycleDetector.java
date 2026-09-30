package com.github.guoyyit.moduleresolverjava.graph;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Detects strongly connected components (cycles) in a dependency graph.
 */
public final class CycleDetector {

    /**
     * Returns one deterministic cycle path per cyclic strongly connected
     * component (self loops included). Every path starts with the
     * lexicographically smallest node of the component, ends with that same
     * node, and is the shortest such cycle; ties are broken by node order.
     */
    public List<List<String>> findCycles(DependencyGraph graph) {
        List<Set<String>> components = tarjan(graph);
        List<List<String>> cycles = new ArrayList<>();
        for (Set<String> component : components) {
            if (isCyclic(component, graph)) {
                cycles.add(canonicalCycle(component, graph));
            }
        }
        cycles.sort((a, b) -> a.get(0).compareTo(b.get(0)));
        return List.copyOf(cycles);
    }

    public Map<String, Set<List<String>>> cyclesByModule(DependencyGraph graph) {
        Map<String, Set<List<String>>> result = new TreeMap<>();
        for (List<String> cycle : findCycles(graph)) {
            for (String node : new TreeSet<>(cycle.subList(0, cycle.size() - 1))) {
                result.computeIfAbsent(node, key -> new HashSet<>()).add(cycle);
            }
        }
        return Map.copyOf(result);
    }

    private static List<Set<String>> tarjan(DependencyGraph graph) {
        Map<String, Integer> index = new HashMap<>();
        Map<String, Integer> lowLink = new HashMap<>();
        Deque<String> stack = new ArrayDeque<>();
        Set<String> onStack = new HashSet<>();
        List<Set<String>> components = new ArrayList<>();

        Deque<Frame> work = new ArrayDeque<>();
        int counter = 0;
        for (String root : new TreeSet<>(graph.modules())) {
            if (index.containsKey(root)) {
                continue;
            }
            work.push(new Frame(root));
            while (!work.isEmpty()) {
                Frame frame = work.peek();
                if (frame.nextEdge == 0) {
                    index.put(frame.node, counter);
                    lowLink.put(frame.node, counter);
                    counter++;
                    stack.push(frame.node);
                    onStack.add(frame.node);
                }
                List<String> neighbors = graph.directDependencies(frame.node);
                if (frame.nextEdge < neighbors.size()) {
                    String next = neighbors.get(frame.nextEdge++);
                    if (!index.containsKey(next)) {
                        work.push(new Frame(next));
                    } else if (onStack.contains(next)) {
                        lowLink.merge(frame.node, index.get(next), Math::min);
                    }
                } else {
                    if (lowLink.get(frame.node).equals(index.get(frame.node))) {
                        Set<String> component = new TreeSet<>();
                        String popped;
                        do {
                            popped = stack.pop();
                            onStack.remove(popped);
                            component.add(popped);
                        } while (!popped.equals(frame.node));
                        components.add(Set.copyOf(component));
                    }
                    work.pop();
                    if (!work.isEmpty()) {
                        Frame parent = work.peek();
                        lowLink.merge(parent.node, lowLink.get(frame.node), Math::min);
                    }
                }
            }
        }
        return components;
    }

    private static boolean isCyclic(Set<String> component, DependencyGraph graph) {
        if (component.size() > 1) {
            return true;
        }
        String only = component.iterator().next();
        return graph.hasEdge(only, only);
    }

    private static List<String> canonicalCycle(Set<String> component, DependencyGraph graph) {
        String start = new TreeSet<>(component).first();

        Map<String, String> predecessor = new HashMap<>();
        Set<String> seen = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        for (String neighbor : graph.directDependencies(start)) {
            if (component.contains(neighbor) && seen.add(neighbor)) {
                predecessor.put(neighbor, start);
                queue.add(neighbor);
            }
        }

        String end = null;
        while (!queue.isEmpty()) {
            String current = queue.poll();
            if (graph.hasEdge(current, start)) {
                end = current;
                break;
            }
            for (String next : graph.directDependencies(current)) {
                if (component.contains(next) && !next.equals(start) && seen.add(next)) {
                    predecessor.put(next, current);
                    queue.add(next);
                }
            }
        }

        List<String> path = new ArrayList<>();
        for (String node = end; node != null; node = predecessor.get(node)) {
            path.add(node);
            if (node.equals(start)) {
                break;
            }
        }
        java.util.Collections.reverse(path);
        path.add(start);
        return List.copyOf(path);
    }

    private static final class Frame {
        private final String node;
        private int nextEdge;

        private Frame(String node) {
            this.node = node;
        }
    }
}

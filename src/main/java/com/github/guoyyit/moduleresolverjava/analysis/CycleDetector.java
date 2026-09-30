package com.github.guoyyit.moduleresolverjava.analysis;

import com.github.guoyyit.moduleresolverjava.graph.ModuleGraph;
import com.github.guoyyit.moduleresolverjava.model.Diagnostic;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Deque;
import java.util.Set;
import java.util.TreeSet;

/**
 * Detects dependency cycles with Tarjan's strongly connected component
 * algorithm. A component with more than one module (or a self-loop) is a
 * cycle; each cycle is reported once.
 */
public final class CycleDetector {

    private java.util.Map<String, Integer> indices;
    private java.util.Map<String, Integer> lowLinks;
    private Deque<String> stack;
    private Set<String> onStack;
    private int counter;
    private List<Set<String>> components;

    public List<Diagnostic> detect(ModuleGraph graph) {
        List<Set<String>> components = tarjan(graph);
        List<Diagnostic> diagnostics = new ArrayList<>();
        for (Set<String> component : components) {
            boolean selfLoop = component.size() == 1
                    && graph.directDependencies(component.iterator().next())
                    .contains(component.iterator().next());
            if (component.size() == 1 && !selfLoop) {
                continue;
            }
            List<String> ordered = new ArrayList<>(component);
            ordered.sort(String::compareTo);
            String first = ordered.get(0);
            String file = graph.declaration(first) == null
                    ? null : graph.declaration(first).file();
            diagnostics.add(new Diagnostic(
                    Diagnostic.Severity.ERROR,
                    "CIRCULAR_DEPENDENCY",
                    "circular dependency detected among modules: "
                            + String.join(" -> ", ordered) + " -> " + first,
                    file, null, null));
        }
        return List.copyOf(diagnostics);
    }

    /** Module names participating in any cycle (deterministic, sorted). */
    public Set<String> modulesInCycles(ModuleGraph graph) {
        List<Set<String>> components = tarjan(graph);
        Set<String> result = new TreeSet<>();
        for (Set<String> component : components) {
            if (component.size() > 1) {
                result.addAll(component);
            } else {
                String only = component.iterator().next();
                if (graph.directDependencies(only).contains(only)) {
                    result.add(only);
                }
            }
        }
        return Set.copyOf(result);
    }

    private List<Set<String>> tarjan(ModuleGraph graph) {
        indices = new java.util.HashMap<>();
        lowLinks = new java.util.HashMap<>();
        stack = new ArrayDeque<>();
        onStack = new TreeSet<>();
        counter = 0;
        components = new ArrayList<>();
        List<String> names = new ArrayList<>(graph.moduleNames());
        names.sort(String::compareTo);
        for (String name : names) {
            if (!indices.containsKey(name)) {
                strongConnect(name, graph);
            }
        }
        components.sort((a, b) -> {
            String firstA = new TreeSet<>(a).first();
            String firstB = new TreeSet<>(b).first();
            return firstA.compareTo(firstB);
        });
        return components;
    }

    private void strongConnect(String module, ModuleGraph graph) {
        indices.put(module, counter);
        lowLinks.put(module, counter);
        counter++;
        stack.push(module);
        onStack.add(module);

        List<String> successors = new ArrayList<>(graph.directDependencies(module));
        successors.sort(String::compareTo);
        for (String successor : successors) {
            if (!graph.moduleNames().contains(successor)) {
                continue;
            }
            if (!indices.containsKey(successor)) {
                strongConnect(successor, graph);
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
            components.add(Set.copyOf(component));
        }
    }
}

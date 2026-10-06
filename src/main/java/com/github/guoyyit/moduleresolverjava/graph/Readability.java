package com.github.guoyyit.moduleresolverjava.graph;

import com.github.guoyyit.moduleresolverjava.model.ModuleDeclaration;
import com.github.guoyyit.moduleresolverjava.model.ModuleDependency;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Computes module readability ("可读模块") implied by
 * {@code requires transitive} edges.
 *
 * <p>Semantics (implied readability): when module M declares
 * {@code requires transitive T}, every module that <em>reads</em> M also
 * reads T — and, recursively, everything T itself propagates. A plain
 * {@code requires} edge makes the target readable only to the declaring
 * module and never propagates further, so indirect packages stay isolated
 * unless every hop past the first one is transitive.
 *
 * <p>Two mutually recursive monotone systems express this exactly:
 * <pre>
 * propagated(M) = ⋃ { {T} ∪ propagated(T) : M requires transitive T }
 * readable(M)   = directDeps(M) ∪ ⋃ { propagated(D) : D ∈ directDeps(M) }
 * </pre>
 * {@code propagated(M)} is what M forwards to its readers; {@code readable(M)}
 * is what M itself can read. Both systems are monotone, so the least
 * fixpoint is unique: the result is identical for any evaluation order,
 * submission order or concurrency, and cycles converge naturally.
 *
 * <p>{@link #computeIncremental} recomputes only the modules in the
 * engine-supplied recompute set and reuses the previous immutable lists for
 * every other module, so incremental cost tracks the affected region instead
 * of the full module count, and repeated commits do not accumulate copies of
 * unchanged sets.
 */
public final class Readability {

    private Readability() {
    }

    /**
     * The two readability tables: {@code readable} is what a module can read
     * itself, {@code propagated} is what it forwards to its readers.
     */
    public record Tables(Map<String, List<String>> readable,
                         Map<String, List<String>> propagated) {
    }

    /**
     * Full computation over every declared module (used for full resolve and
     * rebuild, where every module is in the recompute set).
     */
    public static Tables computeAll(Map<String, ModuleDeclaration> declarations) {
        return compute(declarations, declarations.keySet(), Map.of(), Map.of());
    }

    /**
     * Incremental computation. Modules outside {@code recompute} keep their
     * previous lists (the same objects are shared with the previous
     * snapshot); modules inside are recomputed from the current declarations.
     *
     * <p>Soundness: a module outside the recompute set cannot reach any
     * changed module through requires edges, so every input of its equations
     * is unchanged and its previous values are still the fixpoint values.
     * Modules inside the set are solved as a monotone subsystem with the
     * unchanged sets as constants, which yields exactly the global fixpoint
     * restricted to the set.
     */
    public static Tables computeIncremental(
            Map<String, ModuleDeclaration> declarations,
            Set<String> recompute,
            Map<String, List<String>> previousReadable,
            Map<String, List<String>> previousPropagated) {
        return compute(declarations, recompute, previousReadable, previousPropagated);
    }

    private static Tables compute(Map<String, ModuleDeclaration> declarations,
                                  Set<String> recompute,
                                  Map<String, List<String>> previousReadable,
                                  Map<String, List<String>> previousPropagated) {
        // Transitive edges of the modules being (re)solved.
        Map<String, List<String>> transitiveEdges = new HashMap<>();
        for (String module : recompute) {
            ModuleDeclaration declaration = declarations.get(module);
            if (declaration == null) {
                continue; // removed module: nothing propagated of its own
            }
            List<String> edges = new ArrayList<>();
            for (ModuleDependency dependency : declaration.requires()) {
                if (dependency.transitive()) {
                    edges.add(dependency.name());
                }
            }
            transitiveEdges.put(module, List.copyOf(edges));
        }

        Map<String, List<String>> propagated = new TreeMap<>();
        Map<String, List<String>> readable = new TreeMap<>();
        for (String module : declarations.keySet()) {
            if (!recompute.contains(module)) {
                propagated.put(module, previousPropagated.getOrDefault(module, List.of()));
                readable.put(module, previousReadable.getOrDefault(module, List.of()));
            }
        }

        // propagated(M) is plain reachability from M in the transitive-edge
        // subgraph. A depth-first walk per recomputed module is bounded by
        // the affected region; nodes outside the recompute set contribute
        // their (unchanged) previous sets as constants, so the walk never
        // descends into unchanged territory.
        for (Map.Entry<String, List<String>> entry : transitiveEdges.entrySet()) {
            Set<String> visited = new TreeSet<>();
            ArrayDeque<String> stack = new ArrayDeque<>(entry.getValue());
            while (!stack.isEmpty()) {
                String current = stack.pop();
                if (!visited.add(current)) {
                    continue;
                }
                if (recompute.contains(current)) {
                    List<String> next = transitiveEdges.get(current);
                    if (next != null) {
                        next.forEach(stack::push);
                    }
                } else {
                    visited.addAll(previousPropagated.getOrDefault(current, List.of()));
                }
            }
            propagated.put(entry.getKey(), List.copyOf(visited));
        }

        // readable(M) = directDeps(M) ∪ ⋃ propagated(direct deps); a direct
        // pass over the solved table, no second fixpoint is needed.
        for (String module : transitiveEdges.keySet()) {
            ModuleDeclaration declaration = declarations.get(module);
            Set<String> set = new TreeSet<>();
            for (ModuleDependency dependency : declaration.requires()) {
                set.add(dependency.name());
                List<String> targetPropagated = propagated.get(dependency.name());
                if (targetPropagated == null) {
                    targetPropagated = previousPropagated.getOrDefault(dependency.name(),
                            List.of());
                }
                set.addAll(targetPropagated);
            }
            readable.put(module, List.copyOf(set));
        }
        return new Tables(readable, propagated);
    }
}

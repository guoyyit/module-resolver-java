package com.github.guoyyit.moduleresolverjava.analysis;

import com.github.guoyyit.moduleresolverjava.graph.DependencyGraph;
import com.github.guoyyit.moduleresolverjava.model.Diagnostic;
import com.github.guoyyit.moduleresolverjava.model.ExportedPackage;
import com.github.guoyyit.moduleresolverjava.model.ModuleDeclaration;
import com.github.guoyyit.moduleresolverjava.model.PackageOwnership;
import com.github.guoyyit.moduleresolverjava.model.Position;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Assigns each exported package to exactly one owner using deterministic rules:
 *
 * <ol>
 *   <li>{@code UNIQUE_EXPORTER}: a package exported by a single module belongs
 *       to that module.</li>
 *   <li>{@code PARENT_WINS}: when several modules export the package and exactly
 *       one of them is required (directly or transitively) by every other
 *       contender, that lower-level module owns the package. The shadowing
 *       export of the dependent module is reported as a {@code WARNING}
 *       ({@code SHADOWED_EXPORT}).</li>
 *   <li>{@code AMBIGUOUS_EXPORT}: otherwise (no unique lower-level contender,
 *       e.g. unrelated modules or a cycle) the package is a hard {@code ERROR};
 *       the lexicographically smallest contender is recorded as the tentative
 *       owner so downstream processing stays deterministic.</li>
 * </ol>
 *
 * <p>{@link #resolveIncremental} recomputes only packages whose contender set
 * changed or that have a contender inside the engine's recompute set (their
 * mutual reachability may have changed); every other package keeps the
 * previous {@link PackageOwnership} object, so unaffected conclusions are
 * shared with the previous snapshot.
 */
public final class PackageConflictResolver {

    /**
     * Outcome of an incremental ownership pass: the full ownership table plus
     * the set of packages whose ownership actually changed relative to the
     * previous version.
     */
    public record OwnershipOutcome(Map<String, PackageOwnership> owners,
                                   Set<String> changedPackages) {
    }

    public Map<String, PackageOwnership> resolve(Map<String, ModuleDeclaration> declarations) {
        DependencyGraph graph = DependencyGraph.from(edgesOf(declarations));
        return resolveIncremental(declarations, graph, Map.of(),
                declarations.keySet()).owners();
    }

    /**
     * Incremental ownership resolution.
     *
     * @param previousOwners ownership table of the previous committed version
     *                       (empty for a full resolve)
     * @param recompute      modules whose dependency edges may have changed;
     *                       packages with no contender in this set and an
     *                       unchanged contender list keep the previous object
     */
    public OwnershipOutcome resolveIncremental(
            Map<String, ModuleDeclaration> declarations,
            DependencyGraph graph,
            Map<String, PackageOwnership> previousOwners,
            Set<String> recompute) {
        Map<String, List<String>> exporters = new TreeMap<>();
        Map<String, Position> exportPositions = new HashMap<>();
        for (ModuleDeclaration declaration : new TreeMap<>(declarations).values()) {
            for (ExportedPackage exported : declaration.exports()) {
                exporters.computeIfAbsent(exported.packageName(),
                        key -> new ArrayList<>()).add(declaration.name());
                exportPositions.putIfAbsent(exported.packageName() + "|" + declaration.name(),
                        exported.position());
            }
        }

        Set<String> allPackages = new TreeSet<>(exporters.keySet());
        allPackages.addAll(previousOwners.keySet());

        Map<String, PackageOwnership> result = new TreeMap<>();
        Set<String> changed = new TreeSet<>();
        for (String packageName : allPackages) {
            List<String> contenders = exporters.containsKey(packageName)
                    ? List.copyOf(new TreeSet<>(exporters.get(packageName)))
                    : List.of();
            PackageOwnership previous = previousOwners.get(packageName);
            if (contenders.isEmpty()) {
                if (previous != null) {
                    changed.add(packageName);
                }
                continue;
            }
            boolean contendersUnchanged =
                    previous != null && previous.contenders().equals(contenders);
            boolean anyContenderRecomputed = contenders.stream().anyMatch(recompute::contains);
            if (contendersUnchanged && !anyContenderRecomputed) {
                result.put(packageName, previous);
                continue;
            }
            PackageOwnership fresh = resolveOne(packageName, contenders,
                    reachabilityAmong(contenders, graph), exportPositions);
            if (fresh.equals(previous)) {
                result.put(packageName, previous);
            } else {
                result.put(packageName, fresh);
                changed.add(packageName);
            }
        }
        return new OwnershipOutcome(
                Collections.unmodifiableMap(result), Collections.unmodifiableSet(changed));
    }

    private static Map<String, List<String>> edgesOf(
            Map<String, ModuleDeclaration> declarations) {
        Map<String, List<String>> edges = new TreeMap<>();
        for (ModuleDeclaration declaration : declarations.values()) {
            edges.put(declaration.name(),
                    declaration.requires().stream().map(req -> req.name()).toList());
        }
        return edges;
    }

    /**
     * Reachability restricted to the modules that matter for one package:
     * each contender mapped to the set of modules reachable from it. Single
     * contender packages need no graph walk at all.
     */
    private static Map<String, Set<String>> reachabilityAmong(List<String> contenders,
                                                              DependencyGraph graph) {
        Map<String, Set<String>> reachable = new HashMap<>();
        if (contenders.size() <= 1) {
            return reachable;
        }
        for (String contender : contenders) {
            Set<String> visited = new TreeSet<>();
            ArrayDeque<String> queue = new ArrayDeque<>(graph.directDependencies(contender));
            while (!queue.isEmpty()) {
                String current = queue.poll();
                if (visited.add(current)) {
                    queue.addAll(graph.directDependencies(current));
                }
            }
            reachable.put(contender, Set.copyOf(visited));
        }
        return reachable;
    }

    private static PackageOwnership resolveOne(String packageName,
                                               List<String> contenders,
                                               Map<String, Set<String>> reachable,
                                               Map<String, Position> exportPositions) {
        if (contenders.size() == 1) {
            return new PackageOwnership(packageName, contenders.get(0), List.copyOf(contenders),
                    false, "UNIQUE_EXPORTER", List.of());
        }

        List<String> parents = new ArrayList<>();
        for (String candidate : contenders) {
            boolean requiredByAllOthers = true;
            for (String other : contenders) {
                if (!other.equals(candidate)
                        && !reachable.getOrDefault(other, Set.of()).contains(candidate)) {
                    requiredByAllOthers = false;
                    break;
                }
            }
            if (requiredByAllOthers) {
                parents.add(candidate);
            }
        }

        if (parents.size() == 1) {
            String owner = parents.get(0);
            List<Diagnostic> diagnostics = new ArrayList<>();
            for (String contender : contenders) {
                if (!contender.equals(owner)) {
                    Position position = exportPositions.get(packageName + "|" + contender);
                    diagnostics.add(new Diagnostic(Diagnostic.Severity.WARNING, "SHADOWED_EXPORT",
                            "package '" + packageName + "' is also exported by '" + owner
                                    + "' which this module requires; the dependency export wins",
                            position));
                }
            }
            return new PackageOwnership(packageName, owner, List.copyOf(contenders),
                    true, "PARENT_WINS", List.copyOf(diagnostics));
        }

        String tentativeOwner = contenders.get(0);
        Position position = exportPositions.get(packageName + "|" + tentativeOwner);
        Diagnostic error = new Diagnostic(Diagnostic.Severity.ERROR, "AMBIGUOUS_EXPORT",
                "package '" + packageName + "' is exported by unrelated modules "
                        + contenders + "; no rule can determine a unique owner",
                position);
        return new PackageOwnership(packageName, tentativeOwner, List.copyOf(contenders),
                true, "AMBIGUOUS_EXPORT", List.of(error));
    }
}

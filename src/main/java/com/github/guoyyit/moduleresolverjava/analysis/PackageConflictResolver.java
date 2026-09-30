package com.github.guoyyit.moduleresolverjava.analysis;

import com.github.guoyyit.moduleresolverjava.model.Diagnostic;
import com.github.guoyyit.moduleresolverjava.model.ExportedPackage;
import com.github.guoyyit.moduleresolverjava.model.ModuleDeclaration;
import com.github.guoyyit.moduleresolverjava.model.PackageOwnership;
import com.github.guoyyit.moduleresolverjava.model.Position;
import java.util.ArrayList;
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
 */
public final class PackageConflictResolver {

    public Map<String, PackageOwnership> resolve(Map<String, ModuleDeclaration> declarations) {
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

        Map<String, Set<String>> reachable = computeReachability(declarations);
        Map<String, PackageOwnership> result = new TreeMap<>();
        for (Map.Entry<String, List<String>> entry : exporters.entrySet()) {
            String packageName = entry.getKey();
            List<String> contenders = new ArrayList<>(new TreeSet<>(entry.getValue()));
            result.put(packageName, resolveOne(packageName, contenders, reachable, exportPositions));
        }
        return Map.copyOf(result);
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

    private static Map<String, Set<String>> computeReachability(
            Map<String, ModuleDeclaration> declarations) {
        Map<String, Set<String>> direct = new TreeMap<>();
        for (ModuleDeclaration declaration : declarations.values()) {
            direct.put(declaration.name(), new TreeSet<>(
                    declaration.requires().stream().map(req -> req.name()).toList()));
        }
        Map<String, Set<String>> reachable = new TreeMap<>();
        for (String module : new TreeSet<>(direct.keySet())) {
            reachable.put(module, reach(module, direct));
        }
        return reachable;
    }

    private static Set<String> reach(String start, Map<String, Set<String>> direct) {
        Set<String> visited = new TreeSet<>();
        List<String> queue = new ArrayList<>(direct.getOrDefault(start, Set.of()));
        while (!queue.isEmpty()) {
            String current = queue.remove(0);
            if (visited.add(current)) {
                queue.addAll(direct.getOrDefault(current, Set.of()));
            }
        }
        return Set.copyOf(visited);
    }
}

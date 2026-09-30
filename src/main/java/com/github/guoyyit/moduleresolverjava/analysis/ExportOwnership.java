package com.github.guoyyit.moduleresolverjava.analysis;

import com.github.guoyyit.moduleresolverjava.graph.ModuleGraph;
import com.github.guoyyit.moduleresolverjava.model.Diagnostic;
import com.github.guoyyit.moduleresolverjava.model.ExportDirective;
import com.github.guoyyit.moduleresolverjava.model.ModuleDeclaration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Resolves exported package ownership.
 *
 * <p>Deterministic rule: when several modules export the same package the
 * owner is the lexicographically smallest module name, and every other
 * exporter receives an {@code EXPORT_CONFLICT} diagnostic naming the owner.
 * Only one module may export the same package without conflict.</p>
 */
public final class ExportOwnership {

    /** package -> owner module (only present when exactly one exporter). */
    public Map<String, String> resolveOwners(ModuleGraph graph) {
        Map<String, String> owners = new TreeMap<>();
        for (Map.Entry<String, Set<String>> entry : exporters(graph).entrySet()) {
            if (entry.getValue().size() == 1) {
                owners.put(entry.getKey(), entry.getValue().iterator().next());
            }
        }
        return Map.copyOf(owners);
    }

    /** package -> every module exporting it (sorted, conflicts included). */
    public Map<String, Set<String>> exporters(ModuleGraph graph) {
        Map<String, Set<String>> result = new TreeMap<>();
        for (String module : new TreeSet<>(graph.moduleNames())) {
            ModuleDeclaration declaration = graph.declaration(module);
            for (String exported : distinctPackages(declaration)) {
                result.computeIfAbsent(exported, key -> new TreeSet<>()).add(module);
            }
        }
        return Map.copyOf(result);
    }

    public List<Diagnostic> conflicts(ModuleGraph graph) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        Map<String, Set<String>> all = exporters(graph);
        for (Map.Entry<String, Set<String>> entry : all.entrySet()) {
            Set<String> exporterSet = entry.getValue();
            if (exporterSet.size() <= 1) {
                continue;
            }
            String owner = exporterSet.iterator().next();
            for (String exporter : exporterSet) {
                if (exporter.equals(owner)) {
                    continue;
                }
                ModuleDeclaration declaration = graph.declaration(exporter);
                ExportDirective directive = findDirective(declaration, entry.getKey());
                diagnostics.add(new Diagnostic(
                        Diagnostic.Severity.ERROR,
                        "EXPORT_CONFLICT",
                        "package '" + entry.getKey() + "' is exported by modules "
                                + String.join(", ", exporterSet)
                                + "; deterministic owner is '" + owner
                                + "' (smallest module name), module '" + exporter
                                + "' must not export it",
                        declaration.file(),
                        directive.location().line(),
                        directive.location().column()));
            }
        }
        diagnostics.sort(ExportOwnership::compare);
        return List.copyOf(diagnostics);
    }

    private static Set<String> distinctPackages(ModuleDeclaration declaration) {
        return new TreeSet<>(declaration.exports().stream()
                .map(ExportDirective::packageName)
                .toList());
    }

    private static ExportDirective findDirective(ModuleDeclaration declaration,
                                                 String packageName) {
        return declaration.exports().stream()
                .filter(directive -> directive.packageName().equals(packageName))
                .findFirst()
                .orElseThrow();
    }

    private static int compare(Diagnostic a, Diagnostic b) {
        int byFile = nullSafe(a.file()).compareTo(nullSafe(b.file()));
        if (byFile != 0) {
            return byFile;
        }
        int byLine = Integer.compare(line(a), line(b));
        if (byLine != 0) {
            return byLine;
        }
        return a.message().compareTo(b.message());
    }

    private static int line(Diagnostic diagnostic) {
        return diagnostic.line() == null ? Integer.MAX_VALUE : diagnostic.line();
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}

package com.github.guoyyit.moduleresolverjava.model;

import java.util.List;

/**
 * Stable resolution conclusion for one module.
 *
 * @param readableModules modules whose exported packages this module can
 *                        read: every direct dependency plus everything
 *                        propagated through {@code requires transitive}
 *                        edges. Sorted lexicographically.
 * @param propagatedModules modules this module forwards to its own readers
 *                        through its {@code requires transitive} edges.
 *                        Sorted lexicographically.
 */
public record ModuleResolution(String moduleName,
                               String file,
                               List<String> directDependencies,
                               List<String> transitiveDependencies,
                               List<String> readableModules,
                               List<String> propagatedModules,
                               boolean onCycle,
                               List<List<String>> cycles,
                               List<Diagnostic> diagnostics) {
}

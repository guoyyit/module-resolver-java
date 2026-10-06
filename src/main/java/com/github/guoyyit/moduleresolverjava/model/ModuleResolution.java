package com.github.guoyyit.moduleresolverjava.model;

import java.util.List;

/**
 * Stable resolution conclusion for one module.
 *
 * <p>Dependency conclusions are split into three categories:
 * <ul>
 *   <li>{@code directDependencies}: modules named by this module's
 *       {@code requires} directives.</li>
 *   <li>{@code transitiveDependencies}: modules reachable through any
 *       {@code requires} chain, excluding direct ones.</li>
 *   <li>{@code readableModules}: modules whose exported packages are visible
 *       to this module — direct requirements plus everything propagated
 *       through {@code requires transitive} edges.</li>
 * </ul>
 */
public record ModuleResolution(String moduleName,
                               String file,
                               List<String> directDependencies,
                               List<String> transitiveDependencies,
                               List<String> readableModules,
                               boolean onCycle,
                               List<List<String>> cycles,
                               List<Diagnostic> diagnostics) {

    /**
     * Tolerates snapshots persisted by older versions that predate the
     * {@code readableModules} field (deserialized as {@code null}).
     */
    public ModuleResolution {
        if (readableModules == null) {
            readableModules = List.of();
        }
    }
}

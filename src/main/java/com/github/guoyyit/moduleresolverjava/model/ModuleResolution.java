package com.github.guoyyit.moduleresolverjava.model;

import java.util.List;

/**
 * Stable resolution conclusion for one module.
 */
public record ModuleResolution(String moduleName,
                               String file,
                               List<String> directDependencies,
                               List<String> transitiveDependencies,
                               boolean onCycle,
                               List<List<String>> cycles,
                               List<Diagnostic> diagnostics) {
}

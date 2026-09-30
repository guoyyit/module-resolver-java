package com.github.guoyyit.moduleresolverjava.resolve;

import java.util.List;

/**
 * Per-module resolution outcome. Direct and transitive dependency lists
 * are sorted to stay independent of parse/processing order.
 */
public record ResolvedModule(String name,
                             String file,
                             String contentHash,
                             List<String> directDependencies,
                             List<String> transitiveDependencies,
                             List<String> readableModules,
                             List<String> exportedPackages,
                             boolean inCycle) {
}

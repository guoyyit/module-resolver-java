package com.github.guoyyit.moduleresolverjava.model;

import java.util.List;

/**
 * Resolution verdict for an exported package.
 */
public record PackageOwnership(String packageName,
                               String owner,
                               List<String> contenders,
                               boolean conflict,
                               String rule,
                               List<Diagnostic> diagnostics) {
}

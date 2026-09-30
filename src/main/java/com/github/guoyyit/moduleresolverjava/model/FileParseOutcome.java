package com.github.guoyyit.moduleresolverjava.model;

import java.util.List;

/**
 * Parse outcome of one file: either a declaration or a failure with diagnostics.
 */
public record FileParseOutcome(String file,
                               String contentHash,
                               boolean success,
                               ModuleDeclaration declaration,
                               List<Diagnostic> diagnostics) {
}

package com.github.guoyyit.moduleresolverjava.model;

/**
 * A single parse or validation finding.
 * Determinism rule: diagnostics produced by a snapshot are sorted
 * by (severity, file, line, column, message), so identical inputs always
 * yield identical output regardless of processing order or parallelism.
 */
public record Diagnostic(Severity severity,
                         String code,
                         String message,
                         String file,
                         Integer line,
                         Integer column) {

    public enum Severity {
        ERROR,
        WARNING,
        INFO
    }
}

package com.github.guoyyit.moduleresolverjava.model;

/**
 * A single parser or validation finding.
 */
public record Diagnostic(Severity severity, String code, String message, Position position) {

    public enum Severity {
        ERROR,
        WARNING,
        INFO
    }
}

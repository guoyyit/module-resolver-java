package com.github.guoyyit.moduleresolverjava.model;

/**
 * {@code exports package.name;}
 */
public record ExportDirective(String packageName, SourceLocation location) {
}

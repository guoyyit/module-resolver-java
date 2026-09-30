package com.github.guoyyit.moduleresolverjava.model;

/**
 * {@code requires [transitive] [static] module.name;}
 */
public record RequireDirective(String moduleName,
                               boolean transitive,
                               boolean optional,
                               SourceLocation location) {
}

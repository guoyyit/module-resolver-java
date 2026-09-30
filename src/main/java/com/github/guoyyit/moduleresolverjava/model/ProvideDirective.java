package com.github.guoyyit.moduleresolverjava.model;

/**
 * {@code provides service.Interface with impl.Class;}
 */
public record ProvideDirective(String serviceInterface,
                               String implementation,
                               SourceLocation location) {
}

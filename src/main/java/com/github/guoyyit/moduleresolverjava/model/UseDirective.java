package com.github.guoyyit.moduleresolverjava.model;

/**
 * {@code uses service.Interface;}
 */
public record UseDirective(String serviceInterface, SourceLocation location) {
}

package com.github.guoyyit.moduleresolverjava.model;

/**
 * A {@code requires} declaration.
 */
public record ModuleDependency(String name, boolean optional, Position position) {
}

package com.github.guoyyit.moduleresolverjava.model;

/**
 * A {@code consumes} declaration.
 */
public record ServiceConsumption(String serviceInterface, boolean optional, Position position) {
}

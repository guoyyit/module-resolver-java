package com.github.guoyyit.moduleresolverjava.model;

/**
 * A single {@code provides} implementation entry.
 */
public record ProvidedService(String serviceInterface, String implementation, Position position) {
}

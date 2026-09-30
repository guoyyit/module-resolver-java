package com.github.guoyyit.moduleresolverjava.model;

/**
 * 1-based position inside a module declaration file.
 */
public record Position(String file, int line, int column) {
}

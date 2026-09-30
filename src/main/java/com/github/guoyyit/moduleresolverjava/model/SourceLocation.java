package com.github.guoyyit.moduleresolverjava.model;

/**
 * Position of a syntax element inside a module declaration file.
 * Line and column numbers are 1-based.
 */
public record SourceLocation(String file, int line, int column) {
}

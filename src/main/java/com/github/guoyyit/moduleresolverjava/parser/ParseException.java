package com.github.guoyyit.moduleresolverjava.parser;

import com.github.guoyyit.moduleresolverjava.model.SourceLocation;

/**
 * Thrown when a declaration file cannot be parsed. Carries the exact
 * file location (1-based line/column) and a human-readable reason.
 */
public class ParseException extends RuntimeException {

    private final SourceLocation location;

    public ParseException(String message, SourceLocation location) {
        super(message);
        this.location = location;
    }

    public SourceLocation getLocation() {
        return location;
    }
}

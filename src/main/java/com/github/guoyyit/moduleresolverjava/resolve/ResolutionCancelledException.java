package com.github.guoyyit.moduleresolverjava.resolve;

/**
 * Raised when a cooperative cancellation token is observed during
 * resolution. The update is discarded; the previous committed snapshot
 * remains visible and durable.
 */
public class ResolutionCancelledException extends RuntimeException {

    public ResolutionCancelledException() {
        super("resolution cancelled");
    }
}

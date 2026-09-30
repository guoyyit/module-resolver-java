package com.github.guoyyit.moduleresolverjava.resolve;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Cooperative cancellation token. Long-running resolution checks the
 * flag between module batches; on cancellation the previous committed
 * snapshot remains the only visible state.
 */
public final class CancellationToken {

    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    public void cancel() {
        cancelled.set(true);
    }

    public boolean isCancelled() {
        return cancelled.get();
    }
}

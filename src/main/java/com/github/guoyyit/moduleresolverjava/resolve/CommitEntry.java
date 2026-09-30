package com.github.guoyyit.moduleresolverjava.resolve;

import java.time.Instant;
import java.util.List;

/**
 * Audit trail entry describing one committed update. Kept in a bounded
 * ring inside snapshots so concurrent updates stay traceable.
 */
public record CommitEntry(long version,
                          Instant committedAt,
                          List<String> changedFiles,
                          String trigger) {
}

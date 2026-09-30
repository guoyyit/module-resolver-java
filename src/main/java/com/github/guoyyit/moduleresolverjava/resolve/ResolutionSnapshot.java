package com.github.guoyyit.moduleresolverjava.resolve;

import com.github.guoyyit.moduleresolverjava.model.Diagnostic;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Immutable, fully consistent resolution version. Readers always observe
 * one complete snapshot, never a partial computation.
 */
public record ResolutionSnapshot(long version,
                                 Instant committedAt,
                                 Map<String, ResolvedModule> modules,
                                 Map<String, String> moduleFiles,
                                 Map<String, String> fileContentHashes,
                                 Map<String, String> failedFileHashes,
                                 Map<String, String> packageOwners,
                                 Map<String, ServiceBinding> serviceBindings,
                                 List<Diagnostic> diagnostics,
                                 Map<String, String> parseFailures,
                                 List<CommitEntry> history) {

    public boolean hasErrors() {
        return diagnostics.stream().anyMatch(diagnostic ->
                diagnostic.severity() == Diagnostic.Severity.ERROR);
    }
}

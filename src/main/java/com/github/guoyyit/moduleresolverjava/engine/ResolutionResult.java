package com.github.guoyyit.moduleresolverjava.engine;

import com.github.guoyyit.moduleresolverjava.model.FileParseOutcome;
import com.github.guoyyit.moduleresolverjava.model.ResolutionSnapshot;

import java.util.Map;
import java.util.Set;

/**
 * Result of one (possibly incremental) resolution run.
 */
public record ResolutionResult(ResolutionSnapshot snapshot,
                               Set<String> affectedModules,
                               Map<String, FileParseOutcome> reparsedFiles,
                               boolean cancelled) {
}

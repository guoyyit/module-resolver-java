package com.github.guoyyit.moduleresolverjava.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * One immutable, fully consistent committed resolution version.
 */
public record ResolutionSnapshot(long version,
                                 Instant committedAt,
                                 Map<String, FileParseOutcome> files,
                                 Map<String, ModuleDeclaration> declarations,
                                 Map<String, ModuleResolution> modules,
                                 List<Diagnostic> globalDiagnostics,
                                 Map<String, PackageOwnership> packageOwners,
                                 Map<String, List<ServiceBinding>> serviceBindings) {
}

package com.github.guoyyit.moduleresolverjava.model;

import java.util.List;

/**
 * Parsed content of one module declaration file.
 * Instances are immutable and safe to share across threads.
 */
public record ModuleDeclaration(String name,
                                SourceLocation nameLocation,
                                String file,
                                String contentHash,
                                List<RequireDirective> requires,
                                List<ExportDirective> exports,
                                List<ProvideDirective> provides,
                                List<UseDirective> uses) {
}

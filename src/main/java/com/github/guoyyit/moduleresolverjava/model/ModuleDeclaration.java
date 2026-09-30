package com.github.guoyyit.moduleresolverjava.model;

import java.util.List;

/**
 * Parsed content of one module declaration file.
 */
public record ModuleDeclaration(String name,
                                String file,
                                String contentHash,
                                List<ModuleDependency> requires,
                                List<ExportedPackage> exports,
                                List<ProvidedService> provides,
                                List<ServiceConsumption> consumes) {
}

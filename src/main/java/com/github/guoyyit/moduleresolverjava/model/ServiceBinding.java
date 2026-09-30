package com.github.guoyyit.moduleresolverjava.model;

import java.util.List;

/**
 * Binding verdict for one consuming module / service interface pair.
 */
public record ServiceBinding(String consumer,
                             String serviceInterface,
                             boolean optional,
                             String selectedProvider,
                             String selectedImplementation,
                             List<String> candidateProviders,
                             String status,
                             List<Diagnostic> diagnostics) {
}

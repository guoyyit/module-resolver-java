package com.github.guoyyit.moduleresolverjava.resolve;

import java.util.List;

/**
 * The resolved binding for one consumed service interface.
 *
 * @param serviceInterface fully qualified interface name
 * @param consumerModules  modules declaring {@code uses}
 * @param providerModules  all modules declaring {@code provides} (sorted)
 * @param selectedProvider deterministic choice (smallest provider name),
 *                         or {@code null} when no provider exists
 */
public record ServiceBinding(String serviceInterface,
                             List<String> consumerModules,
                             List<String> providerModules,
                             String selectedProvider) {
}

package com.github.guoyyit.moduleresolverjava.model;

/**
 * A {@code requires} declaration.
 *
 * @param name      required module name
 * @param optional  {@code static} modifier: a missing target only warns
 * @param transitive {@code transitive} modifier: the target's exported
 *                  packages (and everything the target itself can read) are
 *                  propagated to modules that depend on this module
 * @param position  source position of the module name token
 */
public record ModuleDependency(String name, boolean optional, boolean transitive,
                               Position position) {
}

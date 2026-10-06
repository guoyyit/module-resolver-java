package com.github.guoyyit.moduleresolverjava.model;

/**
 * A {@code requires} declaration.
 *
 * @param name       required module name
 * @param optional   {@code static} modifier: a missing target is a warning
 * @param transitive {@code transitive} modifier: the required module's
 *                   exported packages are propagated to modules that
 *                   (transitively) require this one
 * @param position   source position of the module name token
 */
public record ModuleDependency(String name, boolean optional, boolean transitive,
                               Position position) {
}

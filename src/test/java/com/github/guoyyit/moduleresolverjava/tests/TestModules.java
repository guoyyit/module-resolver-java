package com.github.guoyyit.moduleresolverjava.tests;

import com.github.guoyyit.moduleresolverjava.model.ExportDirective;
import com.github.guoyyit.moduleresolverjava.model.ModuleDeclaration;
import com.github.guoyyit.moduleresolverjava.model.ProvideDirective;
import com.github.guoyyit.moduleresolverjava.model.RequireDirective;
import com.github.guoyyit.moduleresolverjava.model.SourceLocation;
import com.github.guoyyit.moduleresolverjava.model.UseDirective;

import java.util.ArrayList;
import java.util.List;

/** Test factory building declarations without going through the parser. */
final class TestModules {

    private TestModules() {
    }

    static ModuleDeclaration module(String name, String... requires) {
        List<RequireDirective> directives = new ArrayList<>();
        for (String require : requires) {
            directives.add(new RequireDirective(require, false, false,
                    new SourceLocation(name + ".mod", 1, 1)));
        }
        return new ModuleDeclaration(name,
                new SourceLocation(name + ".mod", 1, 1),
                name + ".mod", "hash-" + name,
                List.copyOf(directives), List.of(), List.of(), List.of());
    }

    static ModuleDeclaration transitiveModule(String name, String... requires) {
        List<RequireDirective> directives = new ArrayList<>();
        for (String require : requires) {
            directives.add(new RequireDirective(require, true, false,
                    new SourceLocation(name + ".mod", 1, 1)));
        }
        return new ModuleDeclaration(name,
                new SourceLocation(name + ".mod", 1, 1),
                name + ".mod", "hash-" + name,
                List.copyOf(directives), List.of(), List.of(), List.of());
    }

    static ModuleDeclaration full(String name,
                                  List<RequireDirective> requires,
                                  List<ExportDirective> exports,
                                  List<ProvideDirective> provides,
                                  List<UseDirective> uses) {
        return new ModuleDeclaration(name,
                new SourceLocation(name + ".mod", 1, 1),
                name + ".mod", "hash-" + name,
                requires, exports, provides, uses);
    }
}

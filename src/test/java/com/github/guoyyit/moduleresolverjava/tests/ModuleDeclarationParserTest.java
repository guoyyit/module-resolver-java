package com.github.guoyyit.moduleresolverjava.tests;

import com.github.guoyyit.moduleresolverjava.model.ModuleDeclaration;
import com.github.guoyyit.moduleresolverjava.parser.ModuleDeclarationParser;
import com.github.guoyyit.moduleresolverjava.parser.ParseException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModuleDeclarationParserTest {

    private final ModuleDeclarationParser parser = new ModuleDeclarationParser();

    @Test
    void parsesAllDirectiveKinds() {
        String content = """
                // leading comment
                module com.example.app {
                    requires com.example.core;
                    requires transitive com.example.api;
                    requires static com.example.optional;
                    exports com.example.app.ui;
                    provides com.example.api.Greeter with com.example.app.Hello;
                    uses com.example.api.Greeter;
                }
                """;
        ModuleDeclaration declaration = parser.parse(content, "app.mod");

        assertEquals("com.example.app", declaration.name());
        assertEquals(3, declaration.requires().size());
        assertTrue(declaration.requires().get(1).transitive());
        assertTrue(declaration.requires().get(2).optional());
        assertEquals("com.example.app.ui",
                declaration.exports().get(0).packageName());
        assertEquals("com.example.api.Greeter",
                declaration.provides().get(0).serviceInterface());
        assertEquals("com.example.app.Hello",
                declaration.provides().get(0).implementation());
        assertEquals("com.example.api.Greeter",
                declaration.uses().get(0).serviceInterface());
        assertFalse(declaration.contentHash().isBlank());
    }

    @Test
    void reportsExactLineAndColumnForMissingSemicolon() {
        String content = "module com.x {\n    requires com.y\n}\n";
        ParseException exception = assertThrows(ParseException.class,
                () -> parser.parse(content, "x.mod"));

        assertEquals("x.mod", exception.getLocation().file());
        assertEquals(3, exception.getLocation().line());
        assertEquals(1, exception.getLocation().column());
        assertTrue(exception.getMessage().contains("';'"));
    }

    @Test
    void reportsExactPositionOfInvalidDirective() {
        String content = "module com.x {\n    explodes com.y;\n}\n";
        ParseException exception = assertThrows(ParseException.class,
                () -> parser.parse(content, "x.mod"));

        assertEquals(2, exception.getLocation().line());
        assertEquals(5, exception.getLocation().column());
        assertTrue(exception.getMessage().contains("explodes"));
    }

    @Test
    void rejectsInvalidQualifiedNames() {
        String content = "module com..x {\n}\n";
        ParseException exception = assertThrows(ParseException.class,
                () -> parser.parse(content, "x.mod"));
        assertEquals(1, exception.getLocation().line());
        assertEquals(8, exception.getLocation().column());
        assertTrue(exception.getMessage().contains("invalid module name"));
    }

    @Test
    void supportsBlockComments() {
        String content = """
                module com.x { /* inline */
                    /* multi
                       line */ requires com.y;
                }
                """;
        ModuleDeclaration declaration = parser.parse(content, "x.mod");
        assertEquals(1, declaration.requires().size());
    }

    @Test
    void unterminatedBlockCommentIsReported() {
        ParseException exception = assertThrows(ParseException.class,
                () -> parser.parse("module com.x { /* never closed", "x.mod"));
        assertTrue(exception.getMessage().contains("unterminated block comment"));
    }
}

package com.github.guoyyit.moduleresolverjava.tests;

import com.github.guoyyit.moduleresolverjava.model.Diagnostic;
import com.github.guoyyit.moduleresolverjava.model.FileParseOutcome;
import com.github.guoyyit.moduleresolverjava.parser.ManifestParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManifestParserTest {

    private final ManifestParser parser = new ManifestParser();

    @Test
    void parsesWellFormedDeclaration() {
        String content = """
                # a comment
                module com.example.app
                requires static com.example.logging
                requires com.example.core
                exports com.example.api
                provides com.example.spi.Greeter with com.example.impl.EnGreeter, com.example.impl.ZhGreeter
                consumes optional com.example.spi.Greeter
                """;

        FileParseOutcome outcome = parser.parse("app.module", content);

        assertTrue(outcome.success());
        assertNotNull(outcome.declaration());
        assertEquals("com.example.app", outcome.declaration().name());
        assertEquals(2, outcome.declaration().requires().size());
        assertTrue(outcome.declaration().requires().get(0).optional());
        assertFalse(outcome.declaration().requires().get(1).optional());
        assertEquals("com.example.api", outcome.declaration().exports().get(0).packageName());
        assertEquals(2, outcome.declaration().provides().size());
        assertEquals("com.example.impl.ZhGreeter",
                outcome.declaration().provides().get(1).implementation());
        assertTrue(outcome.declaration().consumes().get(0).optional());
        assertEquals(64, outcome.contentHash().length());
    }

    @Test
    void reportsExactPositionForMalformedInput() {
        String content = "module com.example.app\nrequries com.example.core\n";

        FileParseOutcome outcome = parser.parse("a.module", content);

        assertFalse(outcome.success());
        assertNull(outcome.declaration());
        Diagnostic error = outcome.diagnostics().get(0);
        assertEquals(Diagnostic.Severity.ERROR, error.severity());
        assertEquals("UNKNOWN_DIRECTIVE", error.code());
        assertEquals(2, error.position().line());
        assertEquals(1, error.position().column());
        assertEquals("a.module", error.position().file());
    }

    @Test
    void missingModuleHeaderIsAnError() {
        FileParseOutcome outcome = parser.parse("b.module", "exports com.x\n");

        assertFalse(outcome.success());
        assertTrue(outcome.diagnostics().stream().anyMatch(d -> "MISSING_MODULE".equals(d.code())));
    }

    @Test
    void duplicateDirectivesAreWarnings() {
        String content = """
                module com.example.app
                requires com.example.core
                requires com.example.core
                exports com.example.api
                exports com.example.api
                """;

        FileParseOutcome outcome = parser.parse("c.module", content);

        assertTrue(outcome.success());
        assertEquals(1, outcome.declaration().requires().size());
        assertEquals(1, outcome.declaration().exports().size());
        assertEquals(2, outcome.diagnostics().size());
        assertTrue(outcome.diagnostics().stream()
                .allMatch(d -> d.severity() == Diagnostic.Severity.WARNING));
    }

    @Test
    void withInsideInterfaceNameDoesNotBreakProvidesParsing() {
        String content = """
                module com.example.app
                provides com.example.spi.GreeterWithFallback with com.example.ImplOne, com.example.ImplTwo
                """;

        FileParseOutcome outcome = parser.parse("d.module", content);

        assertTrue(outcome.success(), () -> outcome.diagnostics().toString());
        assertEquals("com.example.spi.GreeterWithFallback",
                outcome.declaration().provides().get(0).serviceInterface());
        assertEquals(2, outcome.declaration().provides().size());
        assertEquals("com.example.ImplTwo",
                outcome.declaration().provides().get(1).implementation());
    }
}

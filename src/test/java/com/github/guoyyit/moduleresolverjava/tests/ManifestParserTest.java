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

    @Test
    void parsesTransitiveAndStaticTransitiveRequires() {
        String content = """
                module com.example.app
                requires transitive com.example.core
                requires static transitive com.example.logging
                requires transitive static com.example.metrics
                requires com.example.plain
                """;

        FileParseOutcome outcome = parser.parse("t.module", content);

        assertTrue(outcome.success(), () -> outcome.diagnostics().toString());
        var requires = outcome.declaration().requires();
        assertEquals(4, requires.size());
        assertFalse(requires.get(0).optional());
        assertTrue(requires.get(0).transitive());
        assertEquals("com.example.core", requires.get(0).name());
        assertTrue(requires.get(1).optional());
        assertTrue(requires.get(1).transitive());
        assertTrue(requires.get(2).optional());
        assertTrue(requires.get(2).transitive());
        assertFalse(requires.get(3).optional());
        assertFalse(requires.get(3).transitive());
    }

    @Test
    void unknownRequiresModifierIsAPositionedDistinctError() {
        FileParseOutcome outcome = parser.parse("u.module",
                "module com.example.app\nrequires bogus com.example.core\n");

        assertFalse(outcome.success());
        Diagnostic error = outcome.diagnostics().get(0);
        assertEquals("UNKNOWN_MODIFIER", error.code());
        assertEquals(Diagnostic.Severity.ERROR, error.severity());
        assertEquals(2, error.position().line());
        assertEquals(10, error.position().column());
        assertEquals("u.module", error.position().file());
    }

    @Test
    void repeatedRequiresModifierIsAPositionedDistinctError() {
        FileParseOutcome outcome = parser.parse("v.module",
                "module com.example.app\nrequires transitive transitive com.example.core\n");

        assertFalse(outcome.success());
        Diagnostic error = outcome.diagnostics().get(0);
        assertEquals("DUPLICATE_MODIFIER", error.code());
        assertEquals(2, error.position().line());
        assertEquals(21, error.position().column());
    }

    @Test
    void requiresWithModifierButNoNameIsMalformed() {
        FileParseOutcome outcome = parser.parse("w.module",
                "module com.example.app\nrequires transitive\n");

        assertFalse(outcome.success());
        assertTrue(outcome.diagnostics().stream()
                .anyMatch(d -> "MALFORMED_DIRECTIVE".equals(d.code())
                        && d.position().line() == 2));
    }

    @Test
    void brokenTransitiveSyntaxDoesNotBlockOtherFiles() {
        FileParseOutcome broken = parser.parse("broken.module",
                "module com.broken\nrequires transitive transitive com.x\n");
        FileParseOutcome healthy = parser.parse("healthy.module",
                "module com.healthy\nrequires transitive com.broken\n");

        assertFalse(broken.success());
        assertTrue(healthy.success());
        assertTrue(healthy.declaration().requires().get(0).transitive());
    }
}

package com.github.guoyyit.moduleresolverjava.tests;

import com.github.guoyyit.moduleresolverjava.engine.ResolutionEngine;
import com.github.guoyyit.moduleresolverjava.model.FileParseOutcome;
import com.github.guoyyit.moduleresolverjava.model.ResolutionSnapshot;
import com.github.guoyyit.moduleresolverjava.parser.ManifestParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shipped example manifests must parse cleanly and exhibit the
 * visibility behaviour documented in the README.
 */
class ExamplesSmokeTest {

    @Test
    void examplesParseAndShowPropagation() throws Exception {
        Path dir = Path.of("examples/modules");
        ManifestParser parser = new ManifestParser();
        Map<String, FileParseOutcome> files = new TreeMap<>();
        try (var stream = Files.list(dir)) {
            for (Path path : stream.filter(p -> p.toString().endsWith(".module")).toList()) {
                FileParseOutcome outcome = parser.parse(
                        path.getFileName().toString(), Files.readString(path));
                assertTrue(outcome.success(),
                        () -> path + ": " + outcome.diagnostics());
                files.put(path.getFileName().toString(), outcome);
            }
        }
        assertEquals(5, files.size());

        ResolutionSnapshot snapshot = new ResolutionEngine().fullResolve(files, 0).snapshot();
        // web does not require core directly, but reads it through the
        // framework's requires transitive edge and binds its Greeter.
        assertEquals(List.of("com.example.framework"),
                snapshot.modules().get("com.example.web").directDependencies());
        assertTrue(snapshot.modules().get("com.example.web").readableModules()
                .contains("com.example.core"));
        assertEquals("BOUND",
                snapshot.serviceBindings().get("com.example.web").stream()
                        .filter(b -> b.serviceInterface().equals("com.example.api.Greeter"))
                        .findFirst().orElseThrow().status());
        // app requires core directly: readable without any transitive edge.
        assertTrue(snapshot.modules().get("com.example.app").readableModules()
                .contains("com.example.core"));
    }
}

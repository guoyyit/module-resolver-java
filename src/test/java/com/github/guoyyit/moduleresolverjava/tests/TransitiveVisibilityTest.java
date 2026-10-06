package com.github.guoyyit.moduleresolverjava.tests;

import com.github.guoyyit.moduleresolverjava.engine.ResolutionEngine;
import com.github.guoyyit.moduleresolverjava.engine.ResolutionResult;
import com.github.guoyyit.moduleresolverjava.model.FileParseOutcome;
import com.github.guoyyit.moduleresolverjava.model.ModuleResolution;
import com.github.guoyyit.moduleresolverjava.model.ResolutionSnapshot;
import com.github.guoyyit.moduleresolverjava.model.ServiceBinding;
import com.github.guoyyit.moduleresolverjava.parser.ManifestParser;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Visibility semantics of {@code requires transitive}: the required module's
 * exported packages (and service providers) become readable to modules
 * upstream of the requiring module; plain {@code requires} does not
 * propagate.
 */
class TransitiveVisibilityTest {

    private final ManifestParser parser = new ManifestParser();
    private final ResolutionEngine engine = new ResolutionEngine();

    private FileParseOutcome outcome(String file, String body) {
        FileParseOutcome outcome = parser.parse(file, body);
        assertTrue(outcome.success(), () -> file + ": " + outcome.diagnostics());
        return outcome;
    }

    private Map<String, FileParseOutcome> files(Object... fileAndBody) {
        Map<String, FileParseOutcome> files = new LinkedHashMap<>();
        for (int i = 0; i < fileAndBody.length; i += 2) {
            String file = (String) fileAndBody[i];
            files.put(file, outcome(file, (String) fileAndBody[i + 1]));
        }
        return files;
    }

    @Test
    void transitiveRequiresPropagatesReadabilityUpstream() {
        ResolutionSnapshot snapshot = engine.fullResolve(files(
                "app.module", "module com.app\nrequires com.biz\n",
                "biz.module", "module com.biz\nrequires transitive com.core\n",
                "core.module", "module com.core\nexports pkg.core\n"), 0).snapshot();

        ModuleResolution app = snapshot.modules().get("com.app");
        assertEquals(List.of("com.biz"), app.directDependencies());
        assertEquals(List.of("com.core"), app.transitiveDependencies());
        assertEquals(List.of("com.biz", "com.core"), app.readableModules());

        ModuleResolution biz = snapshot.modules().get("com.biz");
        assertEquals(List.of("com.core"), biz.readableModules());

        ModuleResolution core = snapshot.modules().get("com.core");
        assertEquals(List.of(), core.readableModules());
    }

    @Test
    void plainRequiresDoesNotPropagateReadability() {
        ResolutionSnapshot snapshot = engine.fullResolve(files(
                "app.module", "module com.app\nrequires com.biz\n",
                "biz.module", "module com.biz\nrequires com.core\n",
                "core.module", "module com.core\nexports pkg.core\n"), 0).snapshot();

        ModuleResolution app = snapshot.modules().get("com.app");
        // com.core is still an ordinary transitive dependency ...
        assertEquals(List.of("com.core"), app.transitiveDependencies());
        // ... but its exported packages are not readable to com.app.
        assertEquals(List.of("com.biz"), app.readableModules());
    }

    @Test
    void propagationStopsAtFirstNonTransitiveEdge() {
        ResolutionSnapshot snapshot = engine.fullResolve(files(
                "a.module", "module com.a\nrequires com.b\n",
                "b.module", "module com.b\nrequires transitive com.c\n",
                "c.module", "module com.c\nrequires com.d\n",
                "d.module", "module com.d\nrequires transitive com.e\n",
                "e.module", "module com.e\nexports pkg.e\n"), 0).snapshot();

        // b propagates c; c does not propagate d, so a reads b and c only.
        assertEquals(List.of("com.b", "com.c"), snapshot.modules().get("com.a").readableModules());
        // c reads d directly; d propagates e, so c reads d and e.
        assertEquals(List.of("com.d", "com.e"), snapshot.modules().get("com.c").readableModules());
        assertEquals(List.of("com.e"), snapshot.modules().get("com.d").readableModules());
    }

    @Test
    void visibilityConclusionsAreIdenticalAcrossSubmissionOrders() {
        Map<String, FileParseOutcome> forward = files(
                "a.module", "module com.a\nrequires com.b\n",
                "b.module", "module com.b\nrequires transitive com.c\n",
                "c.module", "module com.c\nrequires transitive com.d\n",
                "d.module", "module com.d\nexports pkg.d\n");
        Map<String, FileParseOutcome> backward = new LinkedHashMap<>();
        backward.put("d.module", forward.get("d.module"));
        backward.put("c.module", forward.get("c.module"));
        backward.put("b.module", forward.get("b.module"));
        backward.put("a.module", forward.get("a.module"));

        ResolutionSnapshot first = engine.fullResolve(forward, 0).snapshot();
        ResolutionSnapshot second = new ResolutionEngine().fullResolve(backward, 0).snapshot();

        assertEquals(first.modules(), second.modules());
        assertEquals(first.modules().get("com.a").readableModules(),
                second.modules().get("com.a").readableModules());
    }

    @Test
    void addingTransitiveModifierRecomputesDownstreamReadability() {
        ResolutionSnapshot base = engine.fullResolve(files(
                "app.module", "module com.app\nrequires com.biz\n",
                "biz.module", "module com.biz\nrequires com.core\n",
                "core.module", "module com.core\nexports pkg.core\n",
                "iso.module", "module com.iso\nexports pkg.iso\n"), 0).snapshot();
        assertEquals(List.of("com.biz"), base.modules().get("com.app").readableModules());

        ResolutionResult result = engine.incrementalResolve(base,
                Map.of("biz.module", outcome("biz.module",
                        "module com.biz\nrequires transitive com.core\n")),
                Set.of());

        ModuleResolution app = result.snapshot().modules().get("com.app");
        assertEquals(List.of("com.biz", "com.core"), app.readableModules());
        // The readability change propagates to the downstream consumer.
        assertTrue(result.affectedModules().contains("com.app"));
        assertTrue(result.affectedModules().contains("com.biz"));
        // The unrelated module is untouched and keeps the very same object.
        assertFalse(result.affectedModules().contains("com.iso"));
        assertSame(base.modules().get("com.iso"),
                result.snapshot().modules().get("com.iso"));
    }

    @Test
    void removingTransitiveModifierShrinksDownstreamReadability() {
        ResolutionSnapshot base = engine.fullResolve(files(
                "app.module", "module com.app\nrequires com.biz\n",
                "biz.module", "module com.biz\nrequires transitive com.core\n",
                "core.module", "module com.core\nexports pkg.core\n"), 0).snapshot();
        assertEquals(List.of("com.biz", "com.core"),
                base.modules().get("com.app").readableModules());

        ResolutionResult result = engine.incrementalResolve(base,
                Map.of("biz.module", outcome("biz.module",
                        "module com.biz\nrequires com.core\n")),
                Set.of());

        assertEquals(List.of("com.biz"),
                result.snapshot().modules().get("com.app").readableModules());
        assertTrue(result.affectedModules().contains("com.app"));
    }

    @Test
    void unaffectedModulesReuseTheSameConclusionObject() {
        ResolutionSnapshot base = engine.fullResolve(files(
                "app.module", "module com.app\nrequires com.biz\n",
                "biz.module", "module com.biz\nrequires transitive com.core\n",
                "core.module", "module com.core\nexports pkg.core\n",
                "iso.module", "module com.iso\nrequires com.core\n"), 0).snapshot();

        // A change confined to com.core's exports: downstream modules are in
        // the affected cone but their conclusions do not change, so even they
        // keep the identical conclusion object.
        ResolutionResult result = engine.incrementalResolve(base,
                Map.of("core.module", outcome("core.module",
                        "module com.core\nexports pkg.core\nexports pkg.extra\n")),
                Set.of());

        assertTrue(result.affectedModules().contains("com.core"));
        assertSame(base.modules().get("com.app"),
                result.snapshot().modules().get("com.app"));
        assertSame(base.modules().get("com.biz"),
                result.snapshot().modules().get("com.biz"));
        assertSame(base.modules().get("com.iso"),
                result.snapshot().modules().get("com.iso"));
    }

    @Test
    void providerReachableOnlyViaPropagationParticipatesInBinding() {
        ResolutionSnapshot snapshot = engine.fullResolve(files(
                "app.module", "module com.app\nrequires com.biz\nconsumes api.Greeter\n",
                "biz.module", "module com.biz\nrequires transitive com.core\n",
                "core.module",
                "module com.core\nexports api\nprovides api.Greeter with impl.GreeterImpl\n"),
                0).snapshot();

        ServiceBinding binding =
                snapshot.serviceBindings().get("com.app").get(0);
        assertEquals("BOUND", binding.status());
        assertEquals("com.core", binding.selectedProvider());
        assertEquals("impl.GreeterImpl", binding.selectedImplementation());
    }

    @Test
    void unreachableProviderDoesNotParticipateInBinding() {
        ResolutionSnapshot snapshot = engine.fullResolve(files(
                "app.module", "module com.app\nrequires com.biz\nconsumes api.Greeter\n",
                "biz.module", "module com.biz\nrequires com.core\n",
                "core.module",
                "module com.core\nexports api\nprovides api.Greeter with impl.GreeterImpl\n"),
                0).snapshot();

        ServiceBinding binding =
                snapshot.serviceBindings().get("com.app").get(0);
        assertEquals("MISSING_REQUIRED", binding.status());
        assertTrue(snapshot.globalDiagnostics().stream()
                .anyMatch(d -> "MISSING_PROVIDER".equals(d.code())));
    }

    @Test
    void serviceBindingFollowsVisibilityWhenPropagationIsToggled() {
        Map<String, FileParseOutcome> initial = files(
                "app.module", "module com.app\nrequires com.biz\nconsumes api.Greeter\n",
                "biz.module", "module com.biz\nrequires com.core\n",
                "core.module",
                "module com.core\nexports api\nprovides api.Greeter with impl.GreeterImpl\n");
        ResolutionSnapshot base = engine.fullResolve(initial, 0).snapshot();
        assertEquals("MISSING_REQUIRED",
                base.serviceBindings().get("com.app").get(0).status());

        ResolutionResult propagated = engine.incrementalResolve(base,
                Map.of("biz.module", outcome("biz.module",
                        "module com.biz\nrequires transitive com.core\n")),
                Set.of());
        ServiceBinding bound =
                propagated.snapshot().serviceBindings().get("com.app").get(0);
        assertEquals("BOUND", bound.status());
        assertEquals("com.core", bound.selectedProvider());
        assertTrue(propagated.affectedModules().contains("com.app"));

        ResolutionResult reverted = engine.incrementalResolve(propagated.snapshot(),
                Map.of("biz.module", outcome("biz.module",
                        "module com.biz\nrequires com.core\n")),
                Set.of());
        assertEquals("MISSING_REQUIRED",
                reverted.snapshot().serviceBindings().get("com.app").get(0).status());
    }

    @Test
    void optionalConsumptionOfUnreachableProviderStaysAWarning() {
        ResolutionSnapshot snapshot = engine.fullResolve(files(
                "app.module",
                "module com.app\nrequires com.biz\nconsumes optional api.Greeter\n",
                "biz.module", "module com.biz\nrequires com.core\n",
                "core.module",
                "module com.core\nexports api\nprovides api.Greeter with impl.GreeterImpl\n"),
                0).snapshot();

        ServiceBinding binding =
                snapshot.serviceBindings().get("com.app").get(0);
        assertEquals("MISSING_OPTIONAL", binding.status());
        assertTrue(binding.diagnostics().stream()
                .allMatch(d -> d.severity()
                        == com.github.guoyyit.moduleresolverjava.model.Diagnostic.Severity.WARNING));
        // Optional misses never block resolution of the rest of the graph.
        assertTrue(snapshot.declarations().containsKey("com.core"));
    }

    /**
     * A scripted sequence of incremental edits (toggling the transitive
     * modifier, adding and removing files) must land on exactly the same
     * conclusions as a full resolution of the final file set, at every step.
     */
    @Test
    void incrementalVisibilityMatchesFullResolutionAtEveryStep() {
        Map<String, String> contents = new LinkedHashMap<>();
        contents.put("m0.module", "module com.m0\nrequires transitive com.m1\nconsumes api.Svc\n");
        contents.put("m1.module", "module com.m1\nrequires transitive com.m2\n");
        contents.put("m2.module", "module com.m2\nrequires com.m3\n");
        contents.put("m3.module",
                "module com.m3\nexports api\nprovides api.Svc with impl.SvcImpl\n");
        contents.put("m4.module", "module com.m4\nrequires transitive com.m3\n");

        ResolutionSnapshot incremental = engine.fullResolve(parseAll(contents), 0).snapshot();
        assertEquivalent(incremental, engine.fullResolve(parseAll(contents), 0).snapshot());

        String[][] edits = {
                // Flip m2 -> m3 to non-propagating: m0/m1 lose readability of m3.
                {"m2.module", "module com.m2\nrequires transitive com.m3\n"},
                // And back.
                {"m2.module", "module com.m2\nrequires com.m3\n"},
                // New downstream consumer of the propagated chain.
                {"m5.module", "module com.m5\nrequires com.m1\nconsumes api.Svc\n"},
                // Remove the middle of the chain.
                {"m1.module", null},
                // Restore it as non-propagating.
                {"m1.module", "module com.m1\nrequires com.m2\n"},
                // Delete the provider.
                {"m3.module", null},
        };
        for (String[] edit : edits) {
            if (edit[1] == null) {
                contents.remove(edit[0]);
                incremental = engine.incrementalResolve(
                        incremental, Map.of(), Set.of(edit[0])).snapshot();
            } else {
                contents.put(edit[0], edit[1]);
                incremental = engine.incrementalResolve(incremental,
                        Map.of(edit[0], parser.parse(edit[0], edit[1])), Set.of()).snapshot();
            }
            ResolutionSnapshot full =
                    engine.fullResolve(parseAll(contents), incremental.version()).snapshot();
            assertEquivalent(incremental, full);
        }
    }

    private Map<String, FileParseOutcome> parseAll(Map<String, String> contents) {
        Map<String, FileParseOutcome> files = new LinkedHashMap<>();
        contents.forEach((file, body) -> files.put(file, parser.parse(file, body)));
        return files;
    }

    private static void assertEquivalent(ResolutionSnapshot incremental,
                                         ResolutionSnapshot full) {
        assertEquals(full.modules(), incremental.modules(), "module conclusions diverged");
        assertEquals(full.packageOwners(), incremental.packageOwners(),
                "package ownership diverged");
        assertEquals(full.serviceBindings(), incremental.serviceBindings(),
                "service bindings diverged");
        assertEquals(full.globalDiagnostics(), incremental.globalDiagnostics(),
                "global diagnostics diverged");
    }

    /**
     * Deterministic-seed random edits (toggling modifiers, adding/removing
     * modules and edges, random service declarations) must always converge to
     * the full-resolution conclusions, regardless of edit order.
     */
    @Test
    void randomizedIncrementalEditsAlwaysMatchFullResolution() {
        java.util.Random random = new java.util.Random(20261006L);
        int moduleCount = 24;
        Map<String, String> contents = new LinkedHashMap<>();
        for (int i = 0; i < moduleCount; i++) {
            contents.put("r" + i + ".module", randomManifest(random, i, moduleCount));
        }

        ResolutionSnapshot incremental = engine.fullResolve(parseAll(contents), 0).snapshot();
        for (int step = 0; step < 120; step++) {
            int module = random.nextInt(moduleCount);
            String file = "r" + module + ".module";
            int action = random.nextInt(10);
            if (action < 2 && contents.containsKey(file)) {
                contents.remove(file);
                incremental = engine.incrementalResolve(
                        incremental, Map.of(), Set.of(file)).snapshot();
            } else {
                String body = randomManifest(random, module, moduleCount);
                contents.put(file, body);
                incremental = engine.incrementalResolve(incremental,
                        Map.of(file, parser.parse(file, body)), Set.of()).snapshot();
            }
            ResolutionSnapshot full =
                    engine.fullResolve(parseAll(contents), incremental.version()).snapshot();
            assertEquivalent(incremental, full);
        }
    }

    private static String randomManifest(java.util.Random random, int self, int moduleCount) {
        StringBuilder body = new StringBuilder("module com.r" + self + "\n");
        int edges = random.nextInt(4);
        for (int e = 0; e < edges; e++) {
            int target = random.nextInt(moduleCount);
            String modifier = switch (random.nextInt(4)) {
                case 1 -> "static ";
                case 2 -> "transitive ";
                case 3 -> "static transitive ";
                default -> "";
            };
            body.append("requires ").append(modifier).append("com.r")
                    .append(target).append("\n");
        }
        if (random.nextBoolean()) {
            body.append("exports pkg.r").append(self % 7).append("\n");
        }
        if (random.nextInt(3) == 0) {
            body.append("provides api.Svc").append(self % 3)
                    .append(" with impl.Svc").append(self).append("\n");
        }
        if (random.nextInt(3) == 0) {
            body.append("consumes api.Svc").append(random.nextInt(3)).append("\n");
        }
        return body.toString();
    }
}

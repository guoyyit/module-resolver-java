package com.github.guoyyit.moduleresolverjava.tests;

import com.github.guoyyit.moduleresolverjava.engine.ResolutionEngine;
import com.github.guoyyit.moduleresolverjava.engine.ResolutionResult;
import com.github.guoyyit.moduleresolverjava.model.FileParseOutcome;
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
 * Visibility semantics of {@code requires transitive}: propagation, isolation,
 * downstream recomputation and binding interaction.
 */
class PropagationVisibilityTest {

    private final ManifestParser parser = new ManifestParser();
    private final ResolutionEngine engine = new ResolutionEngine();

    private FileParseOutcome outcome(String file, String body) {
        return parser.parse(file, body);
    }

    private Map<String, FileParseOutcome> filesOf(String... fileAndBody) {
        Map<String, FileParseOutcome> files = new LinkedHashMap<>();
        for (int i = 0; i < fileAndBody.length; i += 2) {
            files.put(fileAndBody[i], outcome(fileAndBody[i], fileAndBody[i + 1]));
        }
        return files;
    }

    @Test
    void transitiveRequiresPropagatesExportedPackagesUpstream() {
        ResolutionSnapshot snapshot = engine.fullResolve(filesOf(
                "app.module", "module com.app\nrequires com.mid\n",
                "mid.module", "module com.mid\nrequires transitive com.core\n",
                "core.module", "module com.core\nexports pkg.core\n"), 0).snapshot();

        assertEquals(List.of("com.core"), snapshot.modules().get("com.mid").readableModules());
        assertEquals(List.of("com.core", "com.mid"),
                snapshot.modules().get("com.app").readableModules());
        // Ordinary transitive reachability is still reported separately.
        assertEquals(List.of("com.core"),
                snapshot.modules().get("com.app").transitiveDependencies());
        assertEquals(List.of("com.mid"),
                snapshot.modules().get("com.app").directDependencies());
    }

    @Test
    void plainRequiresDoesNotPropagateReadability() {
        ResolutionSnapshot snapshot = engine.fullResolve(filesOf(
                "app.module", "module com.app\nrequires com.mid\n",
                "mid.module", "module com.mid\nrequires com.core\n",
                "core.module", "module com.core\nexports pkg.core\n"), 0).snapshot();

        // Isolation: without the transitive modifier the upstream module
        // cannot read the indirect dependency, even though it is reachable.
        assertEquals(List.of("com.mid"), snapshot.modules().get("com.app").readableModules());
        assertEquals(List.of("com.core"),
                snapshot.modules().get("com.app").transitiveDependencies());
    }

    @Test
    void transitiveChainsPropagateAcrossMultipleHops() {
        ResolutionSnapshot snapshot = engine.fullResolve(filesOf(
                "a.module", "module com.a\nrequires transitive com.b\n",
                "b.module", "module com.b\nrequires transitive com.c\n",
                "c.module", "module com.c\nrequires transitive com.d\n",
                "d.module", "module com.d\nexports pkg.d\n"), 0).snapshot();

        assertEquals(List.of("com.b", "com.c", "com.d"),
                snapshot.modules().get("com.a").readableModules());
        assertEquals(List.of("com.c", "com.d"),
                snapshot.modules().get("com.b").readableModules());
        // A plain hop in the middle stops propagation further upstream:
        // com.b reads com.c (and com.d through com.c's transitive edge), but
        // com.a only reads com.b because com.b does not propagate com.c.
        ResolutionSnapshot broken = engine.fullResolve(filesOf(
                "a.module", "module com.a\nrequires transitive com.b\n",
                "b.module", "module com.b\nrequires com.c\n",
                "c.module", "module com.c\nrequires transitive com.d\n",
                "d.module", "module com.d\nexports pkg.d\n"), 0).snapshot();
        assertEquals(List.of("com.b"),
                broken.modules().get("com.a").readableModules());
        assertEquals(List.of("com.c", "com.d"),
                broken.modules().get("com.b").readableModules());
    }

    @Test
    void serviceBindingIncludesProvidersVisibleOnlyThroughPropagation() {
        Map<String, FileParseOutcome> files = filesOf(
                "consumer.module", "module com.consumer\nrequires com.mid\nconsumes api.Greeter\n",
                "mid.module", "module com.mid\nrequires transitive com.provider\n",
                "provider.module",
                "module com.provider\nexports api\nprovides api.Greeter with impl.GreeterImpl\n");
        ResolutionSnapshot snapshot = engine.fullResolve(files, 0).snapshot();

        ServiceBinding binding =
                snapshot.serviceBindings().get("com.consumer").get(0);
        assertEquals("BOUND", binding.status());
        assertEquals("com.provider", binding.selectedProvider());
        assertEquals("impl.GreeterImpl", binding.selectedImplementation());
    }

    @Test
    void serviceBindingExcludesProvidersHiddenByPlainRequires() {
        Map<String, FileParseOutcome> files = filesOf(
                "consumer.module", "module com.consumer\nrequires com.mid\nconsumes api.Greeter\n",
                "mid.module", "module com.mid\nrequires com.provider\n",
                "provider.module",
                "module com.provider\nexports api\nprovides api.Greeter with impl.GreeterImpl\n");
        ResolutionSnapshot snapshot = engine.fullResolve(files, 0).snapshot();

        ServiceBinding binding =
                snapshot.serviceBindings().get("com.consumer").get(0);
        assertEquals("MISSING_REQUIRED", binding.status());
        assertTrue(snapshot.globalDiagnostics().stream()
                .anyMatch(d -> "MISSING_PROVIDER".equals(d.code())));
    }

    @Test
    void flippingTransitiveModifierRecomputesExactlyTheDownstream() {
        ResolutionSnapshot base = engine.fullResolve(filesOf(
                "app.module", "module com.app\nrequires com.mid\n",
                "mid.module", "module com.mid\nrequires com.core\n",
                "core.module", "module com.core\nexports pkg.core\n",
                "iso.module", "module com.iso\nexports pkg.iso\n"), 0).snapshot();
        assertEquals(List.of("com.mid"), base.modules().get("com.app").readableModules());

        // Add the modifier: the upstream module gains readability. com.mid
        // itself is also recomputed because the set of modules it propagates
        // to its readers changed; everything else keeps its conclusion object.
        ResolutionResult widened = engine.incrementalResolve(base,
                Map.of("mid.module",
                        outcome("mid.module", "module com.mid\nrequires transitive com.core\n")),
                Set.of());
        assertEquals(Set.of("com.app", "com.mid"), widened.affectedModules());
        assertEquals(List.of("com.core", "com.mid"),
                widened.snapshot().modules().get("com.app").readableModules());
        assertEquals(List.of("com.core"),
                widened.snapshot().modules().get("com.mid").propagatedModules());
        assertSame(base.modules().get("com.core"),
                widened.snapshot().modules().get("com.core"));
        assertSame(base.modules().get("com.iso"),
                widened.snapshot().modules().get("com.iso"));

        // Remove it again: readability shrinks back, again only downstream.
        ResolutionResult narrowed = engine.incrementalResolve(widened.snapshot(),
                Map.of("mid.module",
                        outcome("mid.module", "module com.mid\nrequires com.core\n")),
                Set.of());
        assertEquals(Set.of("com.app", "com.mid"), narrowed.affectedModules());
        assertEquals(List.of("com.mid"),
                narrowed.snapshot().modules().get("com.app").readableModules());
        assertEquals(List.of(),
                narrowed.snapshot().modules().get("com.mid").propagatedModules());
    }

    @Test
    void serviceBindingFollowsVisibilityChangesIncrementally() {
        ResolutionSnapshot base = engine.fullResolve(filesOf(
                "consumer.module", "module com.consumer\nrequires com.mid\nconsumes api.Greeter\n",
                "mid.module", "module com.mid\nrequires com.provider\n",
                "provider.module",
                "module com.provider\nexports api\nprovides api.Greeter with impl.GreeterImpl\n"),
                0).snapshot();
        assertEquals("MISSING_REQUIRED",
                base.serviceBindings().get("com.consumer").get(0).status());

        // Provider becomes visible through the new transitive edge.
        ResolutionResult bound = engine.incrementalResolve(base,
                Map.of("mid.module", outcome("mid.module",
                        "module com.mid\nrequires transitive com.provider\n")),
                Set.of());
        ServiceBinding binding =
                bound.snapshot().serviceBindings().get("com.consumer").get(0);
        assertEquals("BOUND", binding.status());
        assertEquals("com.provider", binding.selectedProvider());
        assertTrue(bound.affectedModules().contains("com.consumer"));

        // And hidden again when the modifier is removed.
        ResolutionResult hidden = engine.incrementalResolve(bound.snapshot(),
                Map.of("mid.module", outcome("mid.module",
                        "module com.mid\nrequires com.provider\n")),
                Set.of());
        assertEquals("MISSING_REQUIRED",
                hidden.snapshot().serviceBindings().get("com.consumer").get(0).status());
        assertTrue(hidden.affectedModules().contains("com.consumer"));
    }

    @Test
    void incrementalAndFullResolveAgreeOnVisibilityConclusions() {
        ResolutionSnapshot current = engine.fullResolve(filesOf(
                "a.module", "module com.a\nrequires com.b\n",
                "b.module", "module com.b\nrequires com.c\n",
                "c.module", "module com.c\nexports pkg.c\n"), 0).snapshot();

        // A sequence of incremental edits touching propagation semantics.
        current = engine.incrementalResolve(current, Map.of("b.module",
                outcome("b.module", "module com.b\nrequires transitive com.c\n")), Set.of())
                .snapshot();
        current = engine.incrementalResolve(current, Map.of("d.module",
                outcome("d.module", "module com.d\nrequires transitive com.b\nexports pkg.d\n")),
                Set.of()).snapshot();
        current = engine.incrementalResolve(current, Map.of("a.module",
                outcome("a.module", "module com.a\nrequires transitive com.b\nrequires com.d\n")),
                Set.of()).snapshot();
        current = engine.incrementalResolve(current, Map.of(), Set.of("c.module")).snapshot();
        current = engine.incrementalResolve(current, Map.of("c.module",
                outcome("c.module", "module com.c\nexports pkg.c\nexports pkg.c2\n")),
                Set.of()).snapshot();

        // The same final file set resolved from scratch must produce the
        // exact same conclusions (content-wise).
        ResolutionSnapshot fromScratch = engine.fullResolve(current.files(), 0).snapshot();
        assertEquals(fromScratch.modules(), current.modules());
        assertEquals(fromScratch.serviceBindings(), current.serviceBindings());
        assertEquals(fromScratch.packageOwners(), current.packageOwners());
        assertEquals(fromScratch.globalDiagnostics(), current.globalDiagnostics());
    }

    @Test
    void visibilityIsIndependentOfSubmissionOrder() {
        String[][] manifests = {
                {"app.module", "module com.app\nrequires com.mid\n"},
                {"mid.module", "module com.mid\nrequires transitive com.core\n"},
                {"core.module", "module com.core\nexports pkg.core\n"},
        };
        // Forward order.
        ResolutionSnapshot forward = engine.fullResolve(filesOf(
                "app.module", manifests[0][1],
                "mid.module", manifests[1][1],
                "core.module", manifests[2][1]), 0).snapshot();
        // Reverse order, applied incrementally one file at a time.
        ResolutionSnapshot reverse = engine.fullResolve(Map.of(), 0).snapshot();
        for (int i = manifests.length - 1; i >= 0; i--) {
            reverse = engine.incrementalResolve(reverse,
                    Map.of(manifests[i][0], outcome(manifests[i][0], manifests[i][1])),
                    Set.of()).snapshot();
        }
        assertEquals(forward.modules(), reverse.modules());
        assertEquals(forward.serviceBindings(), reverse.serviceBindings());
        assertEquals(forward.packageOwners(), reverse.packageOwners());
        assertEquals(forward.globalDiagnostics(), reverse.globalDiagnostics());
    }

    @Test
    void repeatedCommitsDoNotDuplicateUnchangedConclusions() {
        ResolutionSnapshot current = engine.fullResolve(filesOf(
                "app.module", "module com.app\nrequires com.mid\n",
                "mid.module", "module com.mid\nrequires transitive com.core\n",
                "core.module", "module com.core\nexports pkg.core\n"), 0).snapshot();
        var appConclusion = current.modules().get("com.app");
        var midConclusion = current.modules().get("com.mid");
        var coreConclusion = current.modules().get("com.core");

        // Many commits that never change conclusions (comment-only edits):
        // every version must keep sharing the very same conclusion objects,
        // so memory stays flat in the number of commits.
        ResolutionSnapshot previous = current;
        for (int i = 0; i < 50; i++) {
            ResolutionSnapshot next = engine.incrementalResolve(previous,
                    Map.of("core.module", outcome("core.module",
                            "module com.core\nexports pkg.core\n# revision " + i + "\n")),
                    Set.of()).snapshot();
            assertSame(appConclusion, next.modules().get("com.app"));
            assertSame(midConclusion, next.modules().get("com.mid"));
            assertSame(coreConclusion, next.modules().get("com.core"));
            previous = next;
        }
        assertEquals(51, previous.version());
    }

    @Test
    void legacyManifestsKeepOwnershipAndDirectVisibilityBehavior() {
        // Manifests written before the transitive modifier existed need no
        // migration: package ownership still follows dependency reachability
        // (PARENT_WINS through a plain chain), and direct dependencies stay
        // readable exactly as before.
        ResolutionSnapshot snapshot = engine.fullResolve(filesOf(
                "app.module", "module com.app\nrequires com.biz\nexports pkg.shared\n",
                "biz.module", "module com.biz\nrequires com.core\n",
                "core.module", "module com.core\nexports pkg.shared\n"), 0).snapshot();

        assertEquals("com.core", snapshot.packageOwners().get("pkg.shared").owner());
        assertEquals("PARENT_WINS", snapshot.packageOwners().get("pkg.shared").rule());
        // Direct dependency readability is unchanged; the indirect module is
        // reachable (transitive dependency) but not readable.
        assertEquals(List.of("com.biz"),
                snapshot.modules().get("com.app").readableModules());
        assertEquals(List.of("com.core"),
                snapshot.modules().get("com.app").transitiveDependencies());
        assertTrue(snapshot.modules().get("com.app").propagatedModules().isEmpty());
    }

    @Test
    void documentedExamplesBehaveAsDocumented() throws Exception {
        // The example manifests referenced by the README must resolve the
        // way the documentation describes: com.example.web reads
        // com.example.core only through the transitive edge of
        // com.example.app and binds its provider.
        Map<String, FileParseOutcome> files = new LinkedHashMap<>();
        for (String name : List.of("app.module", "core.module", "logging.module", "web.module")) {
            files.put(name, parser.parse(name,
                    java.nio.file.Files.readString(
                            java.nio.file.Path.of("examples", "modules", name))));
        }
        ResolutionSnapshot snapshot = engine.fullResolve(files, 0).snapshot();

        assertTrue(snapshot.globalDiagnostics().stream()
                        .noneMatch(d -> d.severity()
                                == com.github.guoyyit.moduleresolverjava.model.Diagnostic.Severity.ERROR),
                () -> "examples must resolve without errors: " + snapshot.globalDiagnostics());
        assertEquals(
                List.of("com.example.app", "com.example.core"),
                snapshot.modules().get("com.example.web").readableModules());
        assertEquals(List.of("com.example.core"),
                snapshot.modules().get("com.example.app").propagatedModules());
        ServiceBinding webBinding =
                snapshot.serviceBindings().get("com.example.web").get(0);
        assertEquals("BOUND", webBinding.status());
        assertEquals("com.example.core", webBinding.selectedProvider());

        // Documented isolation: dropping the transitive modifier hides the
        // core packages from com.example.web and unbinds its service.
        ResolutionResult hidden = engine.incrementalResolve(snapshot,
                Map.of("app.module", parser.parse("app.module",
                        "module com.example.app\nrequires com.example.core\n"
                                + "requires static com.example.logging\n"
                                + "consumes com.example.api.Greeter\n"
                                + "consumes optional com.example.logging.Logger\n")),
                Set.of());
        assertEquals(List.of("com.example.app"),
                hidden.snapshot().modules().get("com.example.web").readableModules());
        assertEquals("MISSING_REQUIRED",
                hidden.snapshot().serviceBindings().get("com.example.web").get(0).status());
    }
}
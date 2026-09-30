package com.github.guoyyit.moduleresolverjava.tests;

import com.github.guoyyit.moduleresolverjava.model.Diagnostic;
import com.github.guoyyit.moduleresolverjava.resolve.CancellationToken;
import com.github.guoyyit.moduleresolverjava.resolve.ResolutionSnapshot;
import com.github.guoyyit.moduleresolverjava.system.IncrementalModuleSystem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IncrementalModuleSystemTest {

    private static String module(String name, String body) {
        return "module " + name + " {\n" + body + "\n}\n";
    }

    private static List<Diagnostic> codes(ResolutionSnapshot snapshot) {
        return snapshot.diagnostics();
    }

    @Test
    void resolvesHealthyModules(@TempDir Path stateDir) {
        IncrementalModuleSystem system = new IncrementalModuleSystem(stateDir);
        system.upsertFile("/modules/core.mod",
                module("com.core", "exports pkg.core;\n"));
        ResolutionSnapshot snapshot = system.upsertFile("/modules/app.mod",
                module("com.app",
                        "requires com.core;\nexports pkg.app;\n"));

        assertEquals(2, snapshot.version());
        assertFalse(snapshot.hasErrors());
        assertEquals(List.of("com.core"),
                snapshot.modules().get("com.app").directDependencies());
        assertTrue(snapshot.packageOwners().containsKey("pkg.core"));
    }

    @Test
    void reportsCycleAndExportConflictAndServiceIssuesEndToEnd(@TempDir Path stateDir) {
        IncrementalModuleSystem system = new IncrementalModuleSystem(stateDir);
        system.upsertFile("/a.mod", module("com.a",
                "requires com.b;\nexports pkg.shared;\n"
                        + "uses svc.Api;\n"));
        system.upsertFile("/b.mod", module("com.b",
                "requires com.a;\nexports pkg.shared;\n"));
        ResolutionSnapshot snapshot = system.upsertFile("/c.mod",
                module("com.c", ""));

        assertTrue(snapshot.hasErrors());
        assertTrue(codes(snapshot).stream().anyMatch(diagnostic ->
                diagnostic.code().equals("CIRCULAR_DEPENDENCY")));
        assertTrue(codes(snapshot).stream().anyMatch(diagnostic ->
                diagnostic.code().equals("EXPORT_CONFLICT")));
        Diagnostic noProvider = codes(snapshot).stream()
                .filter(diagnostic -> diagnostic.code().equals("SERVICE_NO_PROVIDER"))
                .findFirst().orElseThrow();
        assertEquals("/a.mod", noProvider.file());
        assertEquals(4, noProvider.line());
    }

    @Test
    void parseFailureIsRecordedWithLocationAndDoesNotDestroyOthers(@TempDir Path stateDir) {
        IncrementalModuleSystem system = new IncrementalModuleSystem(stateDir);
        system.upsertFile("/good.mod", module("com.good", ""));
        ResolutionSnapshot snapshot = system.upsertFile("/bad.mod",
                "module com.bad {\n    requires ;\n}\n");

        assertTrue(snapshot.parseFailures().containsKey("/bad.mod"));
        String failure = snapshot.parseFailures().get("/bad.mod");
        assertTrue(failure.contains("PARSE_ERROR"));
        assertTrue(failure.contains("/bad.mod:2"));
        assertTrue(snapshot.modules().containsKey("com.good"));

        ResolutionSnapshot fixed = system.upsertFile("/bad.mod",
                module("com.bad", ""));
        assertFalse(fixed.parseFailures().containsKey("/bad.mod"));
        assertTrue(fixed.modules().containsKey("com.bad"));
    }

    @Test
    void unchangedContentIsNoOp(@TempDir Path stateDir) {
        IncrementalModuleSystem system = new IncrementalModuleSystem(stateDir);
        String content = module("com.a", "");
        ResolutionSnapshot first = system.upsertFile("/a.mod", content);
        ResolutionSnapshot second = system.upsertFile("/a.mod", content);
        assertSame(first, second);
    }

    @Test
    void incrementalChangeKeepsUnaffectedModuleResultStable(@TempDir Path stateDir) {
        IncrementalModuleSystem system = new IncrementalModuleSystem(stateDir);
        system.upsertFile("/core.mod", module("com.core",
                "exports pkg.core;\n"));
        system.upsertFile("/app.mod", module("com.app",
                "requires com.core;\n"));
        ResolutionSnapshot before = system.current();

        ResolutionSnapshot after = system.upsertFile("/app.mod",
                module("com.app", "requires com.core;\n// harmless comment\n"));

        assertSame(before.modules().get("com.core"),
                after.modules().get("com.core"));
        assertNotSame(before.modules().get("com.app"),
                after.modules().get("com.app"));
    }

    @Test
    void reverseDependenciesAreRecomputedWhenDependencyChanges(@TempDir Path stateDir) {
        IncrementalModuleSystem system = new IncrementalModuleSystem(stateDir);
        system.upsertFile("/core.mod", module("com.core", ""));
        system.upsertFile("/app.mod", module("com.app",
                "requires com.core;\n"));

        ResolutionSnapshot after = system.upsertFile("/core.mod",
                module("com.core", "requires com.missing;\n"));

        assertTrue(codes(after).stream().anyMatch(diagnostic ->
                diagnostic.code().equals("REQUIRES_MISSING")));
        // com.app is in com.core's reverse dependency closure and was rebuilt
        assertTrue(after.modules().get("com.app").inCycle() == false);
        assertEquals(3, after.version());
    }

    @Test
    void optionalMissingDependencyDoesNotBlock(@TempDir Path stateDir) {
        IncrementalModuleSystem system = new IncrementalModuleSystem(stateDir);
        ResolutionSnapshot snapshot = system.upsertFile("/a.mod",
                module("com.a", "requires static com.maybe;\n"
                        + "uses svc.Api;\n"));

        assertTrue(codes(snapshot).stream().anyMatch(diagnostic ->
                diagnostic.code().equals("REQUIRES_OPTIONAL_MISSING")
                        && diagnostic.severity() == Diagnostic.Severity.INFO));
        assertTrue(codes(snapshot).stream().anyMatch(diagnostic ->
                diagnostic.code().equals("SERVICE_OPTIONAL_MISSING")));
        assertTrue(snapshot.modules().containsKey("com.a"));
    }

    @Test
    void cancellationKeepsPreviousSnapshot(@TempDir Path stateDir) {
        IncrementalModuleSystem system = new IncrementalModuleSystem(stateDir);
        ResolutionSnapshot before = system.upsertFile("/a.mod",
                module("com.a", ""));
        CancellationToken token = new CancellationToken();
        token.cancel();
        ResolutionSnapshot result = system.upsertFile("/b.mod",
                module("com.b", ""), token);
        assertSame(before, result);
        assertEquals(1, system.current().version());
    }

    @Test
    void recoversLastCommittedSnapshotAfterRestart(@TempDir Path stateDir) throws Exception {
        IncrementalModuleSystem first = new IncrementalModuleSystem(stateDir);
        first.upsertFile("/core.mod", module("com.core",
                "exports pkg.core;\n"));
        first.upsertFile("/app.mod", module("com.app",
                "requires com.core;\n"));

        IncrementalModuleSystem second = new IncrementalModuleSystem(stateDir);
        ResolutionSnapshot recovered = second.recover();
        assertEquals(2, recovered.version());
        assertTrue(recovered.modules().containsKey("com.app"));
        assertEquals("com.core",
                recovered.packageOwners().get("pkg.core"));

        // incremental update still works after recovery
        ResolutionSnapshot next = second.upsertFile("/extra.mod",
                module("com.extra", ""));
        assertEquals(3, next.version());
        assertTrue(next.modules().containsKey("com.core"));
    }

    @Test
    void removeFileRecomputesGraph(@TempDir Path stateDir) {
        IncrementalModuleSystem system = new IncrementalModuleSystem(stateDir);
        system.upsertFile("/a.mod", module("com.a", "requires com.b;\n"));
        system.upsertFile("/b.mod", module("com.b", ""));
        assertTrue(system.current().hasErrors() == false);

        ResolutionSnapshot after = system.removeFile("/b.mod");
        assertTrue(after.hasErrors());
        assertFalse(after.modules().containsKey("com.b"));
        assertTrue(codes(after).stream().anyMatch(diagnostic ->
                diagnostic.code().equals("REQUIRES_MISSING")));
    }
}

package com.github.guoyyit.moduleresolverjava.tests;

import com.github.guoyyit.moduleresolverjava.engine.ResolutionEngine;
import com.github.guoyyit.moduleresolverjava.model.Diagnostic;
import com.github.guoyyit.moduleresolverjava.model.ResolutionSnapshot;
import com.github.guoyyit.moduleresolverjava.parser.ManifestParser;
import com.github.guoyyit.moduleresolverjava.service.ResolutionService;
import com.github.guoyyit.moduleresolverjava.state.EventJournal;
import com.github.guoyyit.moduleresolverjava.state.SnapshotStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConcurrencyConsistencyTest {

    private ResolutionService newService(Path stateDir) {
        ResolutionService service = new ResolutionService(new ManifestParser(),
                new ResolutionEngine(),
                new SnapshotStore(stateDir),
                new EventJournal(stateDir));
        service.start();
        return service;
    }

    @Test
    void queriesReadConsistentVersionsDuringUpdates(@TempDir Path stateDir) throws Exception {
        ResolutionService service = newService(stateDir);
        service.upsertFile("seed.module", "module com.seed\n");

        AtomicReference<Throwable> readerFailure = new AtomicReference<>();
        CountDownLatch stop = new CountDownLatch(1);
        Thread reader = new Thread(() -> {
            while (stop.getCount() > 0) {
                try {
                    ResolutionSnapshot snapshot = service.current();
                    boolean internallyConsistent =
                            snapshot.modules().size() == snapshot.declarations().size();
                    if (!internallyConsistent) {
                        readerFailure.set(new AssertionError("torn snapshot read"));
                        return;
                    }
                } catch (Throwable t) {
                    readerFailure.set(t);
                    return;
                }
            }
        });
        reader.setDaemon(true);
        reader.start();

        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<?>> writes = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            final int index = i;
            writes.add(pool.submit(() -> service.upsertFile(
                    "m" + (index % 40) + ".module",
                    "module com.m" + (index % 40) + "\n")));
        }
        for (Future<?> write : writes) {
            write.get(30, TimeUnit.SECONDS);
        }
        stop.countDown();
        reader.join(5000);
        pool.shutdown();

        assertEquals(null, readerFailure.get());
        assertEquals(41, service.current().declarations().size());
        service.stop();
    }

    @Test
    void concurrentUpdatesToSameModuleAreDeterministic(@TempDir Path stateDir) throws Exception {
        ResolutionService first = newService(stateDir);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            final int index = i;
            futures.add(pool.submit(() -> {
                start.await();
                first.upsertFileAsync("a.module",
                        "module com.a\n# revision " + index + "\n").join();
                return null;
            }));
        }
        start.countDown();
        for (Future<?> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();
        long finalVersion = first.currentVersion();
        first.stop();

        ResolutionService second = newService(stateDir);
        assertEquals(finalVersion, second.currentVersion());
        assertEquals(finalVersion, second.current().version());
        second.stop();

        assertEquals(51, finalVersion);
    }

    @Test
    void cancellationDoesNotCorruptIndex(@TempDir Path stateDir) throws Exception {
        ResolutionService service = newService(stateDir);
        service.upsertFile("a.module", "module com.a\n");
        ResolutionSnapshot before = service.current();

        var future = service.upsertFileAsync("b.module", "module com.b\n");
        future.get(10, TimeUnit.SECONDS);

        ResolutionSnapshot afterCommit = service.current();
        assertEquals(before.version() + 1, afterCommit.version());
        assertTrue(afterCommit.declarations().containsKey("com.b"));

        long beforeTimeout = service.currentVersion();
        try {
            service.upsertFile("c.module", "module com.c\n", 1, TimeUnit.NANOSECONDS);
        } catch (IllegalStateException expected) {
            // timeout wrapper; the write may still complete on the writer thread,
            // but it must never corrupt the committed state
        }
        ResolutionSnapshot stable = service.current();
        assertTrue(stable.version() >= beforeTimeout);
        assertFalse(stable.globalDiagnostics().stream()
                .anyMatch(d -> d.severity() == Diagnostic.Severity.ERROR));
        service.stop();
    }

    @Test
    void versionsAreMonotonicAndUnique(@TempDir Path stateDir) {
        ResolutionService service = newService(stateDir);
        Set<Long> versions = ConcurrentHashMap.newKeySet();
        versions.add(service.current().version());
        for (int i = 0; i < 20; i++) {
            versions.add(service.upsertFile("f" + i + ".module",
                    "module com.f" + i + "\n").version());
        }
        assertEquals(21, versions.size());
        service.stop();
    }

    @Test
    void interruptedMidUpdateLeavesCommittedStateUntouched(@TempDir Path stateDir)
            throws Exception {
        ResolutionService service = newService(stateDir);
        service.upsertFile("a.module", "module com.a\n");
        long version = service.currentVersion();

        CountDownLatch entered = new CountDownLatch(1);
        service.setBeforeWriteHook(() -> {
            entered.countDown();
            try {
                Thread.sleep(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        var blocked = service.upsertFileAsync("slow.module", "module com.slow\n");
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        assertTrue(blocked.cancel(true));
        service.setBeforeWriteHook(null);

        ResolutionSnapshot next = service.upsertFile("c.module", "module com.c\n");
        assertEquals(version + 1, next.version());
        assertTrue(next.declarations().containsKey("com.c"));
        assertFalse(next.declarations().containsKey("com.slow"));
        service.stop();
    }

    @Test
    void fullQueueRejectionDoesNotBreakSubsequentWrites(@TempDir Path stateDir) {
        ResolutionService service = new ResolutionService(new ManifestParser(),
                new ResolutionEngine(), new SnapshotStore(stateDir),
                new EventJournal(stateDir), 2);
        service.start();
        int rejected = 0;
        List<java.util.concurrent.CompletableFuture<ResolutionSnapshot>> accepted = new ArrayList<>();
        for (int i = 0; i < 1100; i++) {
            var future = service.upsertFileAsync("x" + i + ".module",
                    "module com.x" + i + "\n");
            if (future.isCompletedExceptionally()) {
                rejected++;
            } else {
                accepted.add(future);
            }
        }
        for (var future : accepted) {
            future.exceptionally(error -> null).join();
        }
        ResolutionSnapshot after = service.upsertFile("y.module", "module com.y\n");
        assertTrue(after.declarations().containsKey("com.y"));
        assertTrue(rejected > 0, "overload should have triggered bounded queue rejection");
        service.stop();
    }

    /**
     * While a writer keeps toggling the {@code transitive} modifier (which
     * changes downstream readability), concurrent readers must always observe
     * one complete, internally consistent snapshot: every module's readable
     * set sits between its direct dependencies and its full reachability,
     * and readability conclusions never appear half-updated.
     */
    @Test
    void queriesDuringVisibilityUpdatesReadConsistentSnapshots(@TempDir Path stateDir)
            throws Exception {
        ResolutionService service = newService(stateDir);
        service.upsertFile("app.module",
                "module com.app\nrequires com.biz\nconsumes api.Greeter\n");
        service.upsertFile("core.module",
                "module com.core\nexports api\nprovides api.Greeter with impl.GreeterImpl\n");

        AtomicReference<Throwable> readerFailure = new AtomicReference<>();
        CountDownLatch stop = new CountDownLatch(1);
        Runnable reader = () -> {
            while (stop.getCount() > 0) {
                try {
                    ResolutionSnapshot snapshot = service.current();
                    if (snapshot.modules().size() != snapshot.declarations().size()) {
                        throw new AssertionError("torn snapshot: modules vs declarations");
                    }
                    for (var resolution : snapshot.modules().values()) {
                        Set<String> direct = new HashSet<>(resolution.directDependencies());
                        Set<String> reachable = new HashSet<>(direct);
                        reachable.addAll(resolution.transitiveDependencies());
                        Set<String> readable = new HashSet<>(resolution.readableModules());
                        if (!readable.containsAll(direct)) {
                            throw new AssertionError("readable set misses direct "
                                    + "dependencies of " + resolution.moduleName());
                        }
                        if (!reachable.containsAll(readable)) {
                            throw new AssertionError("readable set escapes reachability of "
                                    + resolution.moduleName());
                        }
                    }
                    var binding = snapshot.serviceBindings().get("com.app");
                    var app = snapshot.modules().get("com.app");
                    if (binding != null && app != null) {
                        boolean coreReadable =
                                app.readableModules().contains("com.core");
                        boolean bound = binding.stream()
                                .anyMatch(b -> "BOUND".equals(b.status())
                                        && "com.core".equals(b.selectedProvider()));
                        if (coreReadable != bound) {
                            throw new AssertionError("binding/visibility mismatch: readable="
                                    + coreReadable + " bound=" + bound);
                        }
                    }
                } catch (Throwable t) {
                    readerFailure.compareAndSet(null, t);
                    return;
                }
            }
        };
        List<Thread> readers = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Thread thread = new Thread(reader);
            thread.setDaemon(true);
            thread.start();
            readers.add(thread);
        }

        for (int i = 0; i < 60; i++) {
            boolean transitive = i % 2 == 0;
            service.upsertFile("biz.module",
                    "module com.biz\nrequires "
                            + (transitive ? "transitive " : "") + "com.core\n");
        }
        stop.countDown();
        for (Thread reader2 : readers) {
            reader2.join(5000);
        }

        assertEquals(null, readerFailure.get());
        // Final state: last write used plain requires, so com.core is not
        // readable and the service is unbound.
        var app = service.current().modules().get("com.app");
        assertEquals(List.of("com.biz"), app.readableModules());
        assertEquals("MISSING_REQUIRED",
                service.current().serviceBindings().get("com.app").get(0).status());
        service.stop();
    }
}

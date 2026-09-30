package com.github.guoyyit.moduleresolverjava.tests;

import com.github.guoyyit.moduleresolverjava.resolve.ResolutionSnapshot;
import com.github.guoyyit.moduleresolverjava.system.IncrementalModuleSystem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConcurrencyTest {

    private static String declaration(String name) {
        return "module " + name + " {\n}\n";
    }

    @Test
    void concurrentReadersAlwaysObserveCompleteSnapshots(@TempDir Path stateDir)
            throws Exception {
        IncrementalModuleSystem system = new IncrementalModuleSystem(stateDir);
        int modules = 100;
        for (int i = 0; i < modules; i++) {
            system.upsertFile("/m-" + i + ".mod", declaration("com.m" + i));
        }

        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        Set<String> violations = ConcurrentHashMap.newKeySet();
        List<Future<?>> readers = new ArrayList<>();
        for (int reader = 0; reader < 6; reader++) {
            readers.add(pool.submit(() -> {
                try {
                    start.await();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
                for (int observation = 0; observation < 2000; observation++) {
                    ResolutionSnapshot snapshot = system.current();
                    // Every observed snapshot must be internally consistent:
                    // module count equals declared file/module mappings.
                    if (snapshot.modules().size() != snapshot.moduleFiles().size()) {
                        violations.add("inconsistent snapshot v" + snapshot.version());
                    }
                }
            }));
        }
        start.countDown();
        for (Future<?> future : readers) {
            future.get(30, TimeUnit.SECONDS);
        }
        pool.shutdownNow();
        assertTrue(violations.isEmpty(), () -> violations.toString());
    }

    @Test
    void concurrentUpdatesToSameModuleAreDeterministicAndTraceable(@TempDir Path stateDir)
            throws Exception {
        int runs = 20;
        Set<Long> finalVersions = new HashSet<>();
        for (int run = 0; run < runs; run++) {
            Path runDir = stateDir.resolve("run-" + run);
            IncrementalModuleSystem system = new IncrementalModuleSystem(runDir);
            system.recover();
            List<IncrementalModuleSystem.FileUpdate> updates = new ArrayList<>();
            for (int writer = 0; writer < 4; writer++) {
                updates.add(new IncrementalModuleSystem.FileUpdate("/a.mod",
                        "module com.a {\n    // writer " + writer + "\n}\n"));
            }
            // Tie-break rule: smallest content lexicographically = writer 0
            String winner = "module com.a {\n    // writer 0\n}\n";
            ResolutionSnapshot last = system.upsertFiles(updates, null);

            assertEquals(winner, system.contentOf("/a.mod"));
            assertTrue(last.history().stream()
                    .anyMatch(entry -> entry.trigger().startsWith("rejected:")));
            finalVersions.add(last.version());
        }
        assertEquals(1, finalVersions.size());
        assertEquals(1L, finalVersions.iterator().next());
    }

    @Test
    void racingBatchesFromThreadsStillResolveDeterministically(@TempDir Path stateDir)
            throws Exception {
        IncrementalModuleSystem system = new IncrementalModuleSystem(stateDir);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ResolutionSnapshot>> futures = new ArrayList<>();
        Set<String> possibleContents = new HashSet<>();
        for (int writer = 0; writer < 4; writer++) {
            int writerId = writer;
            futures.add(pool.submit(() -> {
                start.await();
                String content = "module com.a {\n    // writer "
                        + writerId + "\n}\n";
                return system.upsertFile("/a.mod", content);
            }));
            possibleContents.add("module com.a {\n    // writer "
                    + writer + "\n}\n");
        }
        start.countDown();
        for (Future<ResolutionSnapshot> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        pool.shutdownNow();
        ResolutionSnapshot last = system.current();
        // Lock serialization means exactly one of the submitted contents wins;
        // which one depends on scheduling, but the outcome is one complete
        // version and every write is traceable in the bounded history.
        assertTrue(possibleContents.contains(system.contentOf("/a.mod")));
        assertEquals(4, last.version());
        assertEquals(4, last.history().size());
        assertTrue(last.history().stream()
                .allMatch(entry -> entry.changedFiles().contains("/a.mod")));
    }


    @Test
    void snapshotVersionsAreMonotonicUnderParallelDifferentFiles(@TempDir Path stateDir)
            throws Exception {
        IncrementalModuleSystem system = new IncrementalModuleSystem(stateDir);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ResolutionSnapshot>> futures = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            int index = i;
            futures.add(pool.submit(() -> {
                start.await();
                return system.upsertFile("/m-" + index + ".mod",
                        declaration("com.m" + index));
            }));
        }
        start.countDown();
        Set<Long> seenVersions = new HashSet<>();
        for (Future<ResolutionSnapshot> future : futures) {
            seenVersions.add(future.get(30, TimeUnit.SECONDS).version());
        }
        pool.shutdownNow();
        assertEquals(50, seenVersions.size());
        assertEquals(50, system.current().version());
        assertEquals(50, system.current().modules().size());
    }
}

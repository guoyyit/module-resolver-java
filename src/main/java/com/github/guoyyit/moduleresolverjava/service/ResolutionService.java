package com.github.guoyyit.moduleresolverjava.service;

import com.github.guoyyit.moduleresolverjava.engine.ResolutionEngine;
import com.github.guoyyit.moduleresolverjava.engine.ResolutionResult;
import com.github.guoyyit.moduleresolverjava.model.FileParseOutcome;
import com.github.guoyyit.moduleresolverjava.model.ResolutionSnapshot;
import com.github.guoyyit.moduleresolverjava.parser.ManifestParser;
import com.github.guoyyit.moduleresolverjava.state.EventJournal;
import com.github.guoyyit.moduleresolverjava.state.SnapshotStore;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Concurrency-safe, durable facade over the resolution engine.
 *
 * <p>Concurrency model:
 * <ul>
 *   <li>Reads are lock-free: {@link #current()} returns the last committed
 *       immutable snapshot; a reader always observes one complete version.</li>
 *   <li>Writes are serialized on a single bounded writer thread in call
 *       (submission) order. Two updates of the same module are therefore
 *       applied in a deterministic order; every applied update is recorded in
 *       the append-only event journal and tagged with a monotonically
 *       increasing snapshot version.</li>
 *   <li>Each write computes the new snapshot purely in memory first. It is
 *       journaled and persisted only after computation succeeds; cancellation
 *       or timeout leaves the committed state untouched (no intermediate
 *       index state is ever visible or durable).</li>
 * </ul>
 */
public final class ResolutionService {

    private static final int QUEUE_CAPACITY = 1024;

    private final ManifestParser parser;
    private final ResolutionEngine engine;
    private final SnapshotStore snapshotStore;
    private final EventJournal eventJournal;

    private final AtomicReference<ResolutionSnapshot> current = new AtomicReference<>();
    private ThreadPoolExecutor writer;
    private final int queueCapacity;

    /**
     * Test hook invoked on the writer thread before computing a write;
     * production code never sets it. A blocking implementation lets tests
     * exercise interruption/cancellation mid-update.
     */
    private volatile Runnable beforeWriteHook;

    public void setBeforeWriteHook(Runnable beforeWriteHook) {
        this.beforeWriteHook = beforeWriteHook;
    }

    public ResolutionService(ManifestParser parser,
                             ResolutionEngine engine,
                             SnapshotStore snapshotStore,
                             EventJournal eventJournal) {
        this(parser, engine, snapshotStore, eventJournal, QUEUE_CAPACITY);
    }

    public ResolutionService(ManifestParser parser,
                             ResolutionEngine engine,
                             SnapshotStore snapshotStore,
                             EventJournal eventJournal,
                             int queueCapacity) {
        this.parser = parser;
        this.engine = engine;
        this.snapshotStore = snapshotStore;
        this.eventJournal = eventJournal;
        this.queueCapacity = queueCapacity;
    }

    @PostConstruct
    public void start() {
        this.writer = new ThreadPoolExecutor(1, 1,
                0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(queueCapacity),
                runnable -> {
                    Thread thread = new Thread(runnable, "resolution-writer");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
        recover();
    }

    @PreDestroy
    public void stop() {
        if (writer != null) {
            writer.shutdown();
            try {
                writer.awaitTermination(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public ResolutionSnapshot current() {
        return current.get();
    }

    public long currentVersion() {
        return current.get().version();
    }

    public ResolutionSnapshot upsertFile(String file, String content) {
        return submit(() -> applyUpsert(file, content)).join();
    }

    public ResolutionSnapshot upsertFile(String file, String content,
                                         long timeout, TimeUnit unit) {
        try {
            return submit(() -> applyUpsert(file, content)).get(timeout, unit);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while updating " + file, e);
        } catch (java.util.concurrent.ExecutionException e) {
            throw new IllegalStateException("update failed for " + file, e.getCause());
        } catch (java.util.concurrent.TimeoutException e) {
            throw new IllegalStateException(
                    "update of " + file + " timed out after " + timeout + " " + unit, e);
        }
    }

    public CompletableFuture<ResolutionSnapshot> upsertFileAsync(String file, String content) {
        return submit(() -> applyUpsert(file, content));
    }

    public ResolutionSnapshot deleteFile(String file) {
        return submit(() -> applyDelete(file)).join();
    }

    public CompletableFuture<ResolutionSnapshot> deleteFileAsync(String file) {
        return submit(() -> applyDelete(file));
    }

    /**
     * Replaces the complete file set atomically (used for bulk import).
     */
    public ResolutionSnapshot rebuild(Map<String, String> files) {
        return submit(() -> applyRebuild(files)).join();
    }

    public List<EventJournal.JournalEvent> journal() {
        return eventJournal.readAll();
    }

    private CompletableFuture<ResolutionSnapshot> submit(RunnableTask task) {
        try {
            CompletableFuture<ResolutionSnapshot> future = new CompletableFuture<>();
            writer.execute(() -> {
                if (future.isDone()) {
                    return;
                }
                Thread worker = Thread.currentThread();
                future.whenComplete((value, error) -> {
                    if (future.isCancelled() && Thread.currentThread() != worker) {
                        worker.interrupt();
                    }
                });
                try {
                    task.run();
                    future.complete(current.get());
                } catch (Throwable t) {
                    future.completeExceptionally(t);
                }
            });
            return future;
        } catch (RejectedExecutionException e) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "resolution write queue is full", e));
        }
    }

    private void applyUpsert(String file, String content) {
        runHook();
        if (Thread.interrupted()) {
            return;
        }
        FileParseOutcome outcome = parser.parse(file, content);
        ResolutionResult result = engine.incrementalResolve(
                current.get(), Map.of(file, outcome), Set.of());
        if (result.cancelled()) {
            Thread.interrupted();
            return;
        }
        commit(new EventJournal.UpsertEvent(System.currentTimeMillis(), file, content),
                result.snapshot());
    }

    private void applyDelete(String file) {
        runHook();
        if (Thread.interrupted()) {
            return;
        }
        ResolutionResult result =
                engine.incrementalResolve(current.get(), Map.of(), Set.of(file));
        if (result.cancelled()) {
            Thread.interrupted();
            return;
        }
        commit(new EventJournal.DeleteEvent(System.currentTimeMillis(), file),
                result.snapshot());
    }

    private void runHook() {
        Runnable hook = beforeWriteHook;
        if (hook != null) {
            hook.run();
        }
    }

    private void applyRebuild(Map<String, String> files) {
        if (Thread.interrupted()) {
            return;
        }
        Map<String, FileParseOutcome> outcomes = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : new LinkedHashMap<>(files).entrySet()) {
            outcomes.put(entry.getKey(), parser.parse(entry.getKey(), entry.getValue()));
        }
        ResolutionResult result = engine.rebuild(current.get(), outcomes);
        if (result.cancelled()) {
            Thread.interrupted();
            return;
        }
        commitRebuild(files, result.snapshot());
    }

    private void commit(EventJournal.JournalEvent event, ResolutionSnapshot snapshot) {
        if (Thread.currentThread().isInterrupted()) {
            return;
        }
        eventJournal.append(event);
        snapshotStore.save(snapshot);
        eventJournal.appendCheckpoint(snapshot.version());
        eventJournal.truncateBefore(snapshot.version());
        current.set(snapshot);
    }

    private void commitRebuild(Map<String, String> files, ResolutionSnapshot snapshot) {
        for (Map.Entry<String, String> entry : files.entrySet()) {
            eventJournal.append(new EventJournal.UpsertEvent(
                    System.currentTimeMillis(), entry.getKey(), entry.getValue()));
        }
        snapshotStore.save(snapshot);
        eventJournal.appendCheckpoint(snapshot.version());
        eventJournal.truncateBefore(snapshot.version());
        current.set(snapshot);
    }

    private void recover() {
        ResolutionSnapshot snapshot = snapshotStore.load().orElse(null);
        List<EventJournal.JournalEvent> events = eventJournal.readAll();

        List<EventJournal.JournalEvent> pending = new ArrayList<>();
        long checkpointVersion = snapshot == null ? 0 : snapshot.version();
        for (EventJournal.JournalEvent event : events) {
            if (event instanceof EventJournal.CheckpointEvent checkpoint) {
                checkpointVersion = Math.max(checkpointVersion, checkpoint.version());
                pending.clear();
            } else {
                pending.add(event);
            }
        }

        if (snapshot == null && pending.isEmpty()) {
            current.set(engine.fullResolve(Map.of(), 0).snapshot());
            return;
        }

        ResolutionSnapshot recovered = snapshot;
        if (recovered == null) {
            Map<String, FileParseOutcome> files = replayIntoFiles(Map.of(), pending);
            recovered = engine.fullResolve(files, 0).snapshot();
        } else {
            for (EventJournal.JournalEvent event : pending) {
                recovered = replayOne(recovered, event);
            }
        }
        snapshotStore.save(recovered);
        eventJournal.appendCheckpoint(recovered.version());
        eventJournal.truncateBefore(recovered.version());
        current.set(recovered);
    }

    private ResolutionSnapshot replayOne(ResolutionSnapshot snapshot,
                                         EventJournal.JournalEvent event) {
        if (event instanceof EventJournal.UpsertEvent upsert) {
            FileParseOutcome outcome = parser.parse(upsert.file(), upsert.content());
            return engine.incrementalResolve(snapshot, Map.of(upsert.file(), outcome), Set.of())
                    .snapshot();
        }
        if (event instanceof EventJournal.DeleteEvent delete) {
            return engine.incrementalResolve(snapshot, Map.of(), Set.of(delete.file())).snapshot();
        }
        return snapshot;
    }

    private Map<String, FileParseOutcome> replayIntoFiles(
            Map<String, FileParseOutcome> initial, List<EventJournal.JournalEvent> events) {
        Map<String, FileParseOutcome> files = new LinkedHashMap<>(initial);
        for (EventJournal.JournalEvent event : events) {
            if (event instanceof EventJournal.UpsertEvent upsert) {
                files.put(upsert.file(), parser.parse(upsert.file(), upsert.content()));
            } else if (event instanceof EventJournal.DeleteEvent delete) {
                files.remove(delete.file());
            }
        }
        return files;
    }

    @FunctionalInterface
    private interface RunnableTask {
        void run();
    }
}

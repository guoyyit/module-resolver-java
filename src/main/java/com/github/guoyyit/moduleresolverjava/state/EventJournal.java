package com.github.guoyyit.moduleresolverjava.state;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Append-only event journal used as a write-ahead log.
 *
 * <p>Events are stored as JSON Lines ({@code journal.logl}). Every append
 * flushes and fsyncs before the corresponding snapshot write, so a committed
 * event survives a process kill. A {@link CheckpointEvent} marks a snapshot
 * version durably stored; on checkpoint the journal prefix is atomically
 * truncated via temp-file replacement.
 */
public final class EventJournal {

    static final String JOURNAL_FILE = "journal.logl";
    private static final String TMP_SUFFIX = ".tmp";

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
    @JsonSubTypes({
            @JsonSubTypes.Type(value = UpsertEvent.class, name = "upsert"),
            @JsonSubTypes.Type(value = DeleteEvent.class, name = "delete"),
            @JsonSubTypes.Type(value = CheckpointEvent.class, name = "checkpoint")
    })
    public sealed interface JournalEvent permits UpsertEvent, DeleteEvent, CheckpointEvent {
        long timestamp();
    }

    public record UpsertEvent(long timestamp, String file, String content) implements JournalEvent {
    }

    public record DeleteEvent(long timestamp, String file) implements JournalEvent {
    }

    public record CheckpointEvent(long timestamp, long version) implements JournalEvent {
    }

    private final Path file;
    private final Path directory;
    private final ObjectMapper mapper = new ObjectMapper();

    public EventJournal(Path directory) {
        this.directory = directory;
        this.file = directory.resolve(JOURNAL_FILE);
    }

    public synchronized void append(JournalEvent event) {
        try {
            Files.createDirectories(directory);
            String line = mapper.writeValueAsString(event) + System.lineSeparator();
            try (var out = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                out.write(line);
                out.flush();
                try (var channel = java.nio.channels.FileChannel.open(file,
                        StandardOpenOption.READ, StandardOpenOption.WRITE)) {
                    channel.force(true);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed to append journal event", e);
        }
    }

    public synchronized void appendCheckpoint(long version) {
        append(new CheckpointEvent(System.currentTimeMillis(), version));
    }

    public synchronized List<JournalEvent> readAll() {
        if (!Files.exists(file)) {
            return List.of();
        }
        List<JournalEvent> events = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                events.add(mapper.readValue(line, JournalEvent.class));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("corrupt journal", e);
        }
        return List.copyOf(events);
    }

    /**
     * Removes all events up to and including the last checkpoint with the
     * given version. Atomic via temp file + move.
     */
    public synchronized void truncateBefore(long checkpointVersion) {
        try {
            if (!Files.exists(file)) {
                return;
            }
            List<JournalEvent> kept = new ArrayList<>();
            boolean seenCheckpoint = false;
            for (JournalEvent event : readAll()) {
                if (event instanceof CheckpointEvent checkpoint
                        && checkpoint.version() <= checkpointVersion) {
                    seenCheckpoint = true;
                    kept.clear();
                } else if (seenCheckpoint) {
                    kept.add(event);
                } else {
                    kept.add(event);
                }
            }
            Path temp = Files.createTempFile(directory, "journal-", TMP_SUFFIX);
            try (var out = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                for (JournalEvent event : kept) {
                    out.write(mapper.writeValueAsString(event));
                    out.write(System.lineSeparator());
                }
                out.flush();
            }
            try {
                Files.move(temp, file,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            try (var channel = java.nio.channels.FileChannel.open(file,
                    StandardOpenOption.READ, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed to truncate journal", e);
        }
    }
}

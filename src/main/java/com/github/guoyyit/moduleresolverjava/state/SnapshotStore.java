package com.github.guoyyit.moduleresolverjava.state;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.guoyyit.moduleresolverjava.model.ResolutionSnapshot;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

/**
 * Atomic persistence of the latest committed {@link ResolutionSnapshot}.
 *
 * <p>Writes go to a unique temporary file in the same directory followed by an
 * atomic {@code ATOMIC_MOVE}; a crash therefore leaves either the previous
 * snapshot or the new one, never a half-written file. Stale temp files from a
 * crashed JVM are removed on load.
 */
public final class SnapshotStore {

    static final String SNAPSHOT_FILE = "snapshot.json";
    private static final String TEMP_SUFFIX = ".tmp";

    private final Path directory;
    private final ObjectMapper mapper;

    public SnapshotStore(Path directory) {
        this.directory = directory;
        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    public void save(ResolutionSnapshot snapshot) {
        try {
            Files.createDirectories(directory);
            Path temp = Files.createTempFile(directory, "snapshot-", TEMP_SUFFIX);
            try {
                mapper.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), snapshot);
                try {
                    Files.move(temp, directory.resolve(SNAPSHOT_FILE),
                            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                    Files.move(temp, directory.resolve(SNAPSHOT_FILE),
                            StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed to persist snapshot", e);
        }
    }

    public Optional<ResolutionSnapshot> load() {
        try {
            cleanupTempFiles();
            Path file = directory.resolve(SNAPSHOT_FILE);
            if (!Files.exists(file)) {
                return Optional.empty();
            }
            return Optional.of(mapper.readValue(file.toFile(), ResolutionSnapshot.class));
        } catch (IOException e) {
            throw new UncheckedIOException("failed to load snapshot", e);
        }
    }

    private void cleanupTempFiles() throws IOException {
        if (!Files.isDirectory(directory)) {
            return;
        }
        try (var stream = Files.list(directory)) {
            stream.filter(path -> path.getFileName().toString().endsWith(TEMP_SUFFIX))
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ignored) {
                            // Best effort; a temp file never participates in recovery.
                        }
                    });
        }
    }
}

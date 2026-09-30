package com.github.guoyyit.moduleresolverjava.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.guoyyit.moduleresolverjava.resolve.ResolutionSnapshot;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Comparator;
import java.util.stream.Stream;
import java.nio.file.Path;

/**
 * Durable snapshot storage with crash-safe atomic commits.
 *
 * <p>Implementation writes a temp JSON file, fsyncs it, then atomically
 * renames over {@code snapshot.json}. A {@code CURRENT} pointer scheme is
 * used so a crash never leaves a half-written visible snapshot: the last
 * fully committed snapshot is always recoverable on restart.</p>
 */
public final class SnapshotStore {

    static final int KEEP_SNAPSHOTS = 10;

    private final Path directory;
    private final Path currentPointer;
    private final Path blobsDirectory;
    private final ObjectMapper objectMapper;

    public SnapshotStore(Path directory) {
        this.directory = directory;
        this.currentPointer = directory.resolve("CURRENT");
        this.blobsDirectory = directory.resolve("blobs");
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .enable(SerializationFeature.INDENT_OUTPUT);
    }

    /**
     * Durable atomic commit:
     * <ol>
     *   <li>write snapshot-vN.json.tmp and force its bytes to disk;</li>
     *   <li>atomic rename onto snapshot-vN.json;</li>
     *   <li>write CURRENT.tmp, fsync, atomic rename over CURRENT.</li>
     * </ol>
     * A crash at any point leaves the previous snapshot and pointer intact;
     * temporary files are ignored and cleaned up on the next commit.
     */
    public void commit(ResolutionSnapshot snapshot) throws IOException {
        Files.createDirectories(directory);
        cleanupTemporaryFiles();

        Path target = directory.resolve("snapshot-v" + snapshot.version() + ".json");
        Path temp = directory.resolve("snapshot-v" + snapshot.version()
                + ".json.tmp-" + ProcessHandle.current().pid());
        try (OutputStream output = Files.newOutputStream(temp,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE)) {
            objectMapper.writeValue(output, snapshot);
            output.flush();
            if (output instanceof java.io.FileOutputStream fileOutput) {
                fileOutput.getFD().sync();
            }
        }
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicUnsupported) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }

        Path pointerTemp = directory.resolve("CURRENT.tmp-" + ProcessHandle.current().pid());
        try (OutputStream output = Files.newOutputStream(pointerTemp,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE)) {
            output.write(target.getFileName().toString().getBytes(StandardCharsets.UTF_8));
            output.write('\n');
            output.flush();
            if (output instanceof java.io.FileOutputStream fileOutput) {
                fileOutput.getFD().sync();
            }
        }
        try {
            Files.move(pointerTemp, currentPointer, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicUnsupported) {
            Files.move(pointerTemp, currentPointer, StandardCopyOption.REPLACE_EXISTING);
        }
        pruneOldSnapshots();
    }

    public ResolutionSnapshot loadLatest() throws IOException {
        Path target;
        if (Files.exists(currentPointer)) {
            String referenced = Files.readString(currentPointer, StandardCharsets.UTF_8)
                    .trim();
            target = directory.resolve(referenced);
            if (!Files.exists(target)) {
                throw new IOException("CURRENT points to missing snapshot: " + referenced);
            }
        } else {
            target = findNewestSnapshot();
            if (target == null) {
                return null;
            }
        }
        return objectMapper.readValue(target.toFile(), ResolutionSnapshot.class);
    }

    public boolean exists() {
        try {
            return Files.exists(currentPointer) || findNewestSnapshot() != null;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Stores raw file content under a content-addressed path
     * ({@code blobs/<sha256>}). Skipped when the blob already exists. The
     * write itself is not individually fsynced: crash safety comes from the
     * ordering "write blob(s), then atomically commit the snapshot that
     * references them". An orphaned or truncated blob left by a crash is
     * either unreferenced (harmless) or detected on recovery by hash
     * mismatch and rewritten on the next commit.
     */
    public String storeBlob(String content) throws IOException {
        Files.createDirectories(blobsDirectory);
        String hash = sha256(content);
        Path target = blobsDirectory.resolve(hash);
        if (Files.exists(target)) {
            return hash;
        }
        Path temp = blobsDirectory.resolve(
                hash + ".tmp-" + ProcessHandle.current().pid());
        Files.write(temp, content.getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicUnsupported) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return hash;
    }

    public String readBlob(String hash) throws IOException {
        String content = Files.readString(blobsDirectory.resolve(hash),
                StandardCharsets.UTF_8);
        if (!sha256(content).equals(hash)) {
            throw new IOException("content blob is corrupt (hash mismatch): " + hash);
        }
        return content;
    }

    public static String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private Path findNewestSnapshot() throws IOException {
        if (!Files.isDirectory(directory)) {
            return null;
        }
        try (Stream<Path> paths = Files.list(directory)) {
            return paths
                    .filter(path -> path.getFileName().toString()
                            .matches("snapshot-v\\d+\\.json"))
                    .max(Comparator.comparingLong(SnapshotStore::versionOf))
                    .orElse(null);
        }
    }

    private static long versionOf(Path path) {
        String name = path.getFileName().toString();
        String digits = name.substring("snapshot-v".length(), name.length() - ".json".length());
        return Long.parseLong(digits);
    }

    private void pruneOldSnapshots() throws IOException {
        String currentName = Files.exists(currentPointer)
                ? Files.readString(currentPointer, StandardCharsets.UTF_8).trim()
                : "";
        try (Stream<Path> paths = Files.list(directory)) {
            paths.filter(path -> path.getFileName().toString()
                            .matches("snapshot-v\\d+\\.json"))
                    .sorted(Comparator.comparingLong(SnapshotStore::versionOf).reversed())
                    .skip(KEEP_SNAPSHOTS)
                    .filter(path -> !path.getFileName().toString().equals(currentName))
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ignored) {
                            // Bounded cleanup is best effort; newer state is unaffected.
                        }
                    });
        }
    }

    private void cleanupTemporaryFiles() throws IOException {
        try (Stream<Path> paths = Files.list(directory)) {
            paths.filter(path -> {
                String name = path.getFileName().toString();
                return name.matches("snapshot-v\\d+\\.json\\.tmp-\\d+")
                        || name.startsWith("CURRENT.tmp-");
            }).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // best effort
                }
            });
        }
    }
}

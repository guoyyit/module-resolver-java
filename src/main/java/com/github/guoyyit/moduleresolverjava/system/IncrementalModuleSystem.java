package com.github.guoyyit.moduleresolverjava.system;

import com.github.guoyyit.moduleresolverjava.model.ModuleDeclaration;
import com.github.guoyyit.moduleresolverjava.model.SourceLocation;
import com.github.guoyyit.moduleresolverjava.parser.ModuleDeclarationParser;
import com.github.guoyyit.moduleresolverjava.parser.ParseException;
import com.github.guoyyit.moduleresolverjava.resolve.CancellationToken;
import com.github.guoyyit.moduleresolverjava.resolve.CommitEntry;
import com.github.guoyyit.moduleresolverjava.resolve.ResolutionCancelledException;
import com.github.guoyyit.moduleresolverjava.resolve.ResolutionSnapshot;
import com.github.guoyyit.moduleresolverjava.resolve.ResolverEngine;
import com.github.guoyyit.moduleresolverjava.store.SnapshotStore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Main facade: parses declaration files, resolves them incrementally,
 * persists every committed snapshot and serves consistent concurrent reads.
 *
 * <p>Updates are serialized by an internal lock; each update either commits a
 * brand-new immutable snapshot (made durable before publication) or leaves
 * the previous snapshot untouched (cancellation, unchanged input). Raw file
 * contents live in memory and in content-addressed blobs on disk, while
 * snapshots reference only content hashes, keeping snapshot commits
 * O(changes) rather than O(total files). Readers take no lock and always
 * observe one complete version.</p>
 */
public final class IncrementalModuleSystem {

    private static final String PARSE_FAILURE_CODE = "PARSE_ERROR";

    private final SnapshotStore store;
    private final ModuleDeclarationParser parser = new ModuleDeclarationParser();
    private final ResolverEngine engine = new ResolverEngine();
    private final Object updateLock = new Object();
    private volatile ResolutionSnapshot current;

    private Map<String, String> fileContents = new HashMap<>();
    private Map<String, ModuleDeclaration> liveDeclarations = new HashMap<>();

    public IncrementalModuleSystem(Path storageDirectory) {
        this.store = new SnapshotStore(storageDirectory);
        this.current = emptySnapshot();
    }

    /** Loads the last committed snapshot and blobs from disk, if present. */
    public ResolutionSnapshot recover() throws IOException {
        synchronized (updateLock) {
            ResolutionSnapshot loaded = store.loadLatest();
            if (loaded != null) {
                this.current = loaded;
                this.fileContents = loadContents(loaded);
                this.liveDeclarations = reparseDeclarations(loaded, this.fileContents);
            }
            return this.current;
        }
    }

    private Map<String, String> loadContents(ResolutionSnapshot snapshot)
            throws IOException {
        Map<String, String> contents = new HashMap<>();
        for (Map.Entry<String, String> entry
                : snapshot.fileContentHashes().entrySet()) {
            contents.put(entry.getKey(), store.readBlob(entry.getValue()));
        }
        return contents;
    }

    private static ResolutionSnapshot emptySnapshot() {
        return new ResolutionSnapshot(
                0, Instant.EPOCH,
                Map.of(), Map.of(), Map.of(), Map.of(),
                Map.of(), Map.of(), List.of(), Map.of(), List.of());
    }

    /** Adds or replaces one declaration file (by file path). */
    public ResolutionSnapshot upsertFile(String file, String content) {
        return upsertFile(file, content, null);
    }

    public ResolutionSnapshot upsertFile(String file,
                                         String content,
                                         CancellationToken token) {
        synchronized (updateLock) {
            String hash = SnapshotStore.sha256(content);
            if (isUnchanged(file, hash)) {
                return current;
            }

            ResolutionSnapshot previous = current;
            Map<String, String> newContents = new HashMap<>(fileContents);
            Map<String, ModuleDeclaration> declarationsByFile =
                    new HashMap<>(liveDeclarations);
            Map<String, String> parseFailures =
                    new TreeMap<>(previous.parseFailures());
            Map<String, String> failedHashes =
                    new TreeMap<>(previous.failedFileHashes());

            Set<String> changedNames = previousDeclaredName(previous, file);
            removeFileState(declarationsByFile, parseFailures, failedHashes, file);

            newContents.put(file, content);
            try {
                ModuleDeclaration declaration = parser.parse(content, file);
                declarationsByFile.put(file, declaration);
                changedNames.add(declaration.name());
                failedHashes.remove(file);
            } catch (ParseException exception) {
                parseFailures.put(file, formatParseError(exception));
                failedHashes.put(file, hash);
            }

            return commit(previous, newContents, declarationsByFile, parseFailures,
                    failedHashes, changedNames, List.of(file), "upsert", token);
        }
    }

    /** Re-scans a directory; files no longer present are removed. */
    public ResolutionSnapshot syncDirectory(Path directory,
                                            CancellationToken token) throws IOException {
        synchronized (updateLock) {
            List<Path> files;
            try (Stream<Path> stream = Files.walk(directory)) {
                files = stream
                        .filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(".mod"))
                        .sorted()
                        .toList();
            }
            ResolutionSnapshot previous = current;
            Map<String, String> newContents = new HashMap<>();
            Map<String, ModuleDeclaration> declarationsByFile = new TreeMap<>();
            Map<String, String> parseFailures = new TreeMap<>();
            Map<String, String> failedHashes = new TreeMap<>();
            Set<String> changedNames = new TreeSet<>();
            List<String> changedFiles = new ArrayList<>();

            for (Path path : files) {
                String file = path.toAbsolutePath().normalize().toString();
                String content = Files.readString(path, StandardCharsets.UTF_8);
                String hash = SnapshotStore.sha256(content);
                newContents.put(file, content);
                if (isUnchangedFile(previous, file, hash)) {
                    copyPreviousState(file, declarationsByFile,
                            parseFailures, failedHashes);
                    continue;
                }
                changedFiles.add(file);
                changedNames.addAll(previousDeclaredName(previous, file));
                parseOrRecord(file, content, hash, declarationsByFile,
                        parseFailures, failedHashes, changedNames);
            }
            for (String oldFile : fileContents.keySet()) {
                if (!newContents.containsKey(oldFile)) {
                    changedFiles.add(oldFile);
                    changedNames.addAll(previousDeclaredName(previous, oldFile));
                }
            }
            if (changedFiles.isEmpty()) {
                return previous;
            }
            return commit(previous, newContents, declarationsByFile, parseFailures,
                    failedHashes, changedNames, changedFiles, "sync:" + directory, token);
        }
    }

    public ResolutionSnapshot removeFile(String file) {
        synchronized (updateLock) {
            ResolutionSnapshot previous = current;
            if (!fileContents.containsKey(file)
                    && !previous.parseFailures().containsKey(file)) {
                return previous;
            }
            Map<String, String> newContents = new HashMap<>(fileContents);
            Map<String, ModuleDeclaration> declarationsByFile =
                    new HashMap<>(liveDeclarations);
            Map<String, String> parseFailures =
                    new TreeMap<>(previous.parseFailures());
            Map<String, String> failedHashes =
                    new TreeMap<>(previous.failedFileHashes());
            Set<String> changedNames = previousDeclaredName(previous, file);
            newContents.remove(file);
            removeFileState(declarationsByFile, parseFailures, failedHashes, file);
            return commit(previous, newContents, declarationsByFile, parseFailures,
                    failedHashes, changedNames, List.of(file), "remove", null);
        }
    }

    /** Lock-free consistent read of the current committed version. */
    public ResolutionSnapshot current() {
        return current;
    }

    /** Raw content of one tracked file, as of the current committed snapshot. */
    public String contentOf(String file) {
        return fileContents.get(file);
    }

    /**
     * Applies a batch of writes atomically. When the same file appears more
     * than once the deterministic resolution rule is: the lexicographically
     * smallest content wins; every other write is recorded in the commit
     * history as {@code rejected}. This makes concurrent updates to the same
     * module independent of thread scheduling and fully traceable.
     */
    public ResolutionSnapshot upsertFiles(List<FileUpdate> updates,
                                          CancellationToken token) {
        synchronized (updateLock) {
            ResolutionSnapshot previous = current;
            TreeMap<String, String> winners = new TreeMap<>();
            List<String> rejected = new ArrayList<>();
            for (FileUpdate update : updates) {
                String existing = winners.get(update.file());
                if (existing == null || update.content().compareTo(existing) < 0) {
                    winners.put(update.file(), update.content());
                } else {
                    rejected.add(update.file());
                }
            }

            Map<String, String> newContents = new HashMap<>(fileContents);
            Map<String, ModuleDeclaration> declarationsByFile =
                    new HashMap<>(liveDeclarations);
            Map<String, String> parseFailures =
                    new TreeMap<>(previous.parseFailures());
            Map<String, String> failedHashes =
                    new TreeMap<>(previous.failedFileHashes());
            Set<String> changedNames = new TreeSet<>();
            List<String> changedFiles = new ArrayList<>();

            for (Map.Entry<String, String> entry : winners.entrySet()) {
                String file = entry.getKey();
                String content = entry.getValue();
                String hash = SnapshotStore.sha256(content);
                if (isUnchangedFile(previous, file, hash)) {
                    continue;
                }
                changedFiles.add(file);
                changedNames.addAll(previousDeclaredName(previous, file));
                newContents.put(file, content);
                removeFileState(declarationsByFile, parseFailures, failedHashes, file);
                parseOrRecord(file, content, hash, declarationsByFile,
                        parseFailures, failedHashes, changedNames);
            }
            ResolutionSnapshot committed = commit(previous, newContents,
                    declarationsByFile, parseFailures, failedHashes, changedNames,
                    changedFiles, "batch", token);
            if (!rejected.isEmpty()) {
                appendRejectionTrace(committed, rejected);
            }
            return current;
        }
    }

    /** One file write submitted as part of a batch update. */
    public record FileUpdate(String file, String content) {
    }

    private void appendRejectionTrace(ResolutionSnapshot committed,
                                      List<String> rejected) {
        List<CommitEntry> history = new ArrayList<>(committed.history());
        for (String file : rejected) {
            history.add(new CommitEntry(committed.version(), committed.committedAt(),
                    List.of(file),
                    "rejected:concurrent-write-lost-deterministic-tiebreak"));
        }
        if (history.size() > ResolverEngine.HISTORY_LIMIT) {
            history = history.subList(history.size() - ResolverEngine.HISTORY_LIMIT,
                    history.size());
        }
        ResolutionSnapshot annotated = new ResolutionSnapshot(
                committed.version(), committed.committedAt(), committed.modules(),
                committed.moduleFiles(), committed.fileContentHashes(),
                committed.failedFileHashes(), committed.packageOwners(),
                committed.serviceBindings(), committed.diagnostics(),
                committed.parseFailures(), List.copyOf(history));
        try {
            store.commit(annotated);
        } catch (IOException exception) {
            throw new IllegalStateException("failed to persist audit trace", exception);
        }
        current = annotated;
    }

    private boolean isUnchanged(String file, String hash) {
        ResolutionSnapshot snapshot = current;
        if (hash.equals(snapshot.failedFileHashes().get(file))) {
            return true;
        }
        return hash.equals(snapshot.fileContentHashes().get(file));
    }

    private static boolean isUnchangedFile(ResolutionSnapshot snapshot, String file,
                                           String hash) {
        if (hash.equals(snapshot.failedFileHashes().get(file))) {
            return true;
        }
        return hash.equals(snapshot.fileContentHashes().get(file));
    }

    private static Map<String, ModuleDeclaration> reparseDeclarations(
            ResolutionSnapshot snapshot, Map<String, String> contents) {
        Map<String, ModuleDeclaration> result = new TreeMap<>();
        ModuleDeclarationParser reparser = new ModuleDeclarationParser();
        for (Map.Entry<String, String> entry : contents.entrySet()) {
            if (snapshot.parseFailures().containsKey(entry.getKey())) {
                continue;
            }
            try {
                result.put(entry.getKey(),
                        reparser.parse(entry.getValue(), entry.getKey()));
            } catch (ParseException ignored) {
                // previously successful content must still parse
            }
        }
        return result;
    }

    private void copyPreviousState(String file,
                                   Map<String, ModuleDeclaration> declarations,
                                   Map<String, String> parseFailures,
                                   Map<String, String> failedHashes) {
        ResolutionSnapshot previous = current;
        if (previous.parseFailures().containsKey(file)) {
            parseFailures.put(file, previous.parseFailures().get(file));
            failedHashes.put(file, previous.failedFileHashes().get(file));
            return;
        }
        ModuleDeclaration declaration = liveDeclarations.get(file);
        if (declaration != null) {
            declarations.put(file, declaration);
        }
    }

    private static void removeFileState(Map<String, ModuleDeclaration> declarations,
                                        Map<String, String> parseFailures,
                                        Map<String, String> failedHashes,
                                        String file) {
        declarations.remove(file);
        parseFailures.remove(file);
        failedHashes.remove(file);
    }

    private static Set<String> previousDeclaredName(ResolutionSnapshot snapshot,
                                                    String file) {
        Set<String> names = new TreeSet<>();
        for (Map.Entry<String, String> entry : snapshot.moduleFiles().entrySet()) {
            if (entry.getValue().equals(file)) {
                names.add(entry.getKey());
            }
        }
        return names;
    }

    private void parseOrRecord(String file,
                               String content,
                               String hash,
                               Map<String, ModuleDeclaration> declarations,
                               Map<String, String> parseFailures,
                               Map<String, String> failedHashes,
                               Set<String> changedNames) {
        try {
            ModuleDeclaration declaration = parser.parse(content, file);
            declarations.put(file, declaration);
            changedNames.add(declaration.name());
        } catch (ParseException exception) {
            parseFailures.put(file, formatParseError(exception));
            failedHashes.put(file, hash);
        }
    }

    private static String formatParseError(ParseException exception) {
        SourceLocation location = exception.getLocation();
        return PARSE_FAILURE_CODE + ": " + exception.getMessage()
                + " @ " + location.file() + ":" + location.line()
                + ":" + location.column();
    }

    private ResolutionSnapshot commit(ResolutionSnapshot previous,
                                      Map<String, String> newContents,
                                      Map<String, ModuleDeclaration> declarationsByFile,
                                      Map<String, String> parseFailures,
                                      Map<String, String> failedHashes,
                                      Set<String> changedNames,
                                      List<String> changedFiles,
                                      String trigger,
                                      CancellationToken token) {
        Map<String, String> contentHashes = new TreeMap<>();
        try {
            for (Map.Entry<String, String> entry : newContents.entrySet()) {
                contentHashes.put(entry.getKey(), store.storeBlob(entry.getValue()));
            }
        } catch (IOException exception) {
            throw new IllegalStateException("failed to persist declaration content",
                    exception);
        }

        long newVersion = previous.version() + 1;
        CommitEntry entry = new CommitEntry(newVersion, Instant.now(),
                List.copyOf(changedFiles), trigger);
        try {
            ResolutionSnapshot snapshot = engine.resolve(
                    newVersion, contentHashes, declarationsByFile, parseFailures,
                    failedHashes, changedNames, previous, entry, token);
            store.commit(snapshot);
            this.liveDeclarations = new HashMap<>(declarationsByFile);
            this.fileContents = new HashMap<>(newContents);
            current = snapshot;
            return snapshot;
        } catch (ResolutionCancelledException cancelled) {
            return previous;
        } catch (IOException ioException) {
            throw new IllegalStateException("failed to persist resolution snapshot",
                    ioException);
        }
    }
}

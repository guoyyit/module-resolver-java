package com.github.guoyyit.moduleresolverjava.parser;

import com.github.guoyyit.moduleresolverjava.model.Diagnostic;
import com.github.guoyyit.moduleresolverjava.model.ExportedPackage;
import com.github.guoyyit.moduleresolverjava.model.FileParseOutcome;
import com.github.guoyyit.moduleresolverjava.model.ModuleDeclaration;
import com.github.guoyyit.moduleresolverjava.model.ModuleDependency;
import com.github.guoyyit.moduleresolverjava.model.Position;
import com.github.guoyyit.moduleresolverjava.model.ProvidedService;
import com.github.guoyyit.moduleresolverjava.model.ServiceConsumption;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Parses one module declaration file into a {@link FileParseOutcome}.
 *
 * <p>Declaration grammar (line oriented, UTF-8, {@code #} starts a comment):
 * <pre>
 * module   &lt;module-name&gt;
 * requires [static] [transitive] &lt;module-name&gt;
 * exports  &lt;package-name&gt;
 * provides &lt;interface&gt; with &lt;impl&gt;[, &lt;impl&gt; ...]
 * consumes [optional] &lt;interface&gt;
 * </pre>
 *
 * <p>The parser never throws for malformed input: every parse failure is
 * reported as a positioned {@link Diagnostic} (severity {@code ERROR}).
 * Duplicate declarations are {@code WARNING}s and do not fail the parse.
 * If any error exists, the returned outcome carries no declaration.
 */
public final class ManifestParser {

    private static final Set<String> DIRECTIVES =
            Set.of("module", "requires", "exports", "provides", "consumes");

    private static final Pattern QUALIFIED_NAME =
            Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*");

    public FileParseOutcome parse(String file, String content) {
        String safeFile = file == null ? "<unknown>" : file;
        String safeContent = content == null ? "" : content;
        List<Diagnostic> diagnostics = new ArrayList<>();

        String moduleName = null;
        List<ModuleDependency> requires = new ArrayList<>();
        List<ExportedPackage> exports = new ArrayList<>();
        List<ProvidedService> provides = new ArrayList<>();
        List<ServiceConsumption> consumes = new ArrayList<>();

        Set<String> seenRequires = new LinkedHashSet<>();
        Set<String> seenExports = new LinkedHashSet<>();
        Set<String> seenProvidesImpl = new LinkedHashSet<>();
        Set<String> seenConsumes = new LinkedHashSet<>();

        String[] lines = safeContent.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            int lineNo = i + 1;
            String raw = lines[i].replace("\r", "");
            int hashIndex = raw.indexOf('#');
            String line = hashIndex >= 0 ? raw.substring(0, hashIndex) : raw;
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int column = leadingWhitespace(line) + 1;
            List<String> tokens = tokenize(trimmed);
            if (tokens.isEmpty()) {
                continue;
            }
            String keyword = tokens.get(0);
            Position keywordPos = new Position(safeFile, lineNo, column);
            if (!DIRECTIVES.contains(keyword)) {
                error(diagnostics, keywordPos, "UNKNOWN_DIRECTIVE",
                        "unknown directive '" + keyword + "'");
                continue;
            }

            switch (keyword) {
                case "module" -> {
                    if (tokens.size() != 2) {
                        error(diagnostics, keywordPos, "MALFORMED_DIRECTIVE",
                                "'module' expects exactly one name, got " + (tokens.size() - 1)
                                        + " token(s)");
                        break;
                    }
                    String name = tokens.get(1);
                    Position namePos = tokenPosition(safeFile, lineNo, column, trimmed, 1);
                    if (!isQualifiedName(name)) {
                        error(diagnostics, namePos, "INVALID_NAME",
                                "invalid module name '" + name + "'");
                        break;
                    }
                    if (moduleName != null) {
                        error(diagnostics, keywordPos, "DUPLICATE_MODULE",
                                "duplicate module directive; module already declared as '"
                                        + moduleName + "'");
                        break;
                    }
                    moduleName = name;
                }
                case "requires" -> {
                    boolean optional = false;
                    boolean transitive = false;
                    int nameIndex = 1;
                    while (nameIndex < tokens.size()) {
                        String modifier = tokens.get(nameIndex);
                        if ("static".equals(modifier) && !optional) {
                            optional = true;
                            nameIndex++;
                        } else if ("transitive".equals(modifier) && !transitive) {
                            transitive = true;
                            nameIndex++;
                        } else {
                            break;
                        }
                    }
                    if (tokens.size() != nameIndex + 1) {
                        error(diagnostics, keywordPos, "MALFORMED_DIRECTIVE",
                                "'requires' expects 'requires [static] [transitive] <module-name>'");
                        break;
                    }
                    String name = tokens.get(nameIndex);
                    Position namePos =
                            tokenPosition(safeFile, lineNo, column, trimmed, nameIndex);
                    if (!isQualifiedName(name)) {
                        error(diagnostics, namePos, "INVALID_NAME",
                                "invalid required module name '" + name + "'");
                        break;
                    }
                    if (seenRequires.add(name)) {
                        requires.add(new ModuleDependency(name, optional, transitive,
                                tokenPosition(safeFile, lineNo, column, trimmed, nameIndex)));
                    } else {
                        warning(diagnostics, namePos, "DUPLICATE_REQUIRES",
                                "duplicate requires '" + name + "' ignored");
                    }
                }
                case "exports" -> {
                    if (tokens.size() != 2) {
                        error(diagnostics, keywordPos, "MALFORMED_DIRECTIVE",
                                "'exports' expects exactly one package name");
                        break;
                    }
                    String packageName = tokens.get(1);
                    Position packagePos = tokenPosition(safeFile, lineNo, column, trimmed, 1);
                    if (!isQualifiedName(packageName)) {
                        error(diagnostics, packagePos, "INVALID_NAME",
                                "invalid exported package name '" + packageName + "'");
                        break;
                    }
                    if (seenExports.add(packageName)) {
                        exports.add(new ExportedPackage(packageName, packagePos));
                    } else {
                        warning(diagnostics, packagePos, "DUPLICATE_EXPORTS",
                                "duplicate exports '" + packageName + "' ignored");
                    }
                }
                case "provides" -> {
                    if (tokens.size() < 4 || !"with".equals(tokens.get(2))) {
                        error(diagnostics, keywordPos, "MALFORMED_DIRECTIVE",
                                "'provides' expects 'provides <interface> with <impl>[, <impl> ...]'");
                        break;
                    }
                    String serviceInterface = tokens.get(1);
                    Position interfacePos = tokenPosition(safeFile, lineNo, column, trimmed, 1);
                    if (!isQualifiedName(serviceInterface)) {
                        error(diagnostics, interfacePos, "INVALID_NAME",
                                "invalid service interface name '" + serviceInterface + "'");
                        break;
                    }
                    String remainder = substringAfterToken(trimmed, 2);
                    String[] impls = remainder.split(",");
                    for (String rawImpl : impls) {
                        String impl = rawImpl.trim();
                        if (impl.isEmpty()) {
                            continue;
                        }
                        if (!isQualifiedName(impl)) {
                            error(diagnostics, interfacePos, "INVALID_NAME",
                                    "invalid implementation name '" + impl + "' for service '"
                                            + serviceInterface + "'");
                            continue;
                        }
                        String dedupeKey = serviceInterface + "|" + impl;
                        if (seenProvidesImpl.add(dedupeKey)) {
                            provides.add(new ProvidedService(serviceInterface, impl, interfacePos));
                        } else {
                            warning(diagnostics, interfacePos, "DUPLICATE_PROVIDES",
                                    "duplicate provides '" + impl + "' for '"
                                            + serviceInterface + "' ignored");
                        }
                    }
                }
                case "consumes" -> {
                    boolean optional = false;
                    int nameIndex = 1;
                    if (tokens.size() >= 2 && "optional".equals(tokens.get(1))) {
                        optional = true;
                        nameIndex = 2;
                    }
                    if (tokens.size() != nameIndex + 1) {
                        error(diagnostics, keywordPos, "MALFORMED_DIRECTIVE",
                                "'consumes' expects 'consumes [optional] <interface>'");
                        break;
                    }
                    String serviceInterface = tokens.get(nameIndex);
                    Position interfacePos =
                            tokenPosition(safeFile, lineNo, column, trimmed, nameIndex);
                    if (!isQualifiedName(serviceInterface)) {
                        error(diagnostics, interfacePos, "INVALID_NAME",
                                "invalid consumed service interface '" + serviceInterface + "'");
                        break;
                    }
                    if (seenConsumes.add(serviceInterface)) {
                        consumes.add(new ServiceConsumption(serviceInterface, optional, interfacePos));
                    } else {
                        warning(diagnostics, interfacePos, "DUPLICATE_CONSUMES",
                                "duplicate consumes '" + serviceInterface + "' ignored");
                    }
                }
                default -> throw new AssertionError(keyword);
            }
        }

        if (moduleName == null) {
            error(diagnostics, new Position(safeFile, 1, 1), "MISSING_MODULE",
                    "file does not contain a 'module <name>' declaration");
        }

        boolean hasErrors = diagnostics.stream()
                .anyMatch(d -> d.severity() == Diagnostic.Severity.ERROR);
        ModuleDeclaration declaration = null;
        if (!hasErrors) {
            declaration = new ModuleDeclaration(moduleName, safeFile, sha256(safeContent),
                    List.copyOf(requires), List.copyOf(exports),
                    List.copyOf(provides), List.copyOf(consumes));
        }
        return new FileParseOutcome(safeFile, sha256(safeContent), !hasErrors,
                declaration, List.copyOf(diagnostics));
    }

    private static void error(List<Diagnostic> diagnostics, Position position,
                              String code, String message) {
        diagnostics.add(new Diagnostic(Diagnostic.Severity.ERROR, code, message, position));
    }

    private static void warning(List<Diagnostic> diagnostics, Position position,
                                String code, String message) {
        diagnostics.add(new Diagnostic(Diagnostic.Severity.WARNING, code, message, position));
    }

    private static int leadingWhitespace(String line) {
        int count = 0;
        while (count < line.length() && Character.isWhitespace(line.charAt(count))) {
            count++;
        }
        return count;
    }

    private static List<String> tokenize(String line) {
        List<String> tokens = new ArrayList<>();
        for (String token : line.split("\\s+")) {
            if (!token.isEmpty()) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private static Position tokenPosition(String file, int lineNo, int indent,
                                          String trimmed, int tokenIndex) {
        int offset = 0;
        int seen = 0;
        int index = 0;
        while (index < trimmed.length() && seen < tokenIndex) {
            if (Character.isWhitespace(trimmed.charAt(index))) {
                while (index < trimmed.length() && Character.isWhitespace(trimmed.charAt(index))) {
                    index++;
                }
                seen++;
                offset = index;
            } else {
                index++;
            }
        }
        return new Position(file, lineNo, indent + offset + 1);
    }

    /**
     * Returns the source text following the {@code tokenIndex}-th whitespace
     * separated token (e.g. for {@code provides X with a, b} token index 2
     * returns {@code a, b}).
     */
    private static String substringAfterToken(String trimmed, int tokenIndex) {
        int index = 0;
        int seen = 0;
        while (index < trimmed.length()) {
            while (index < trimmed.length() && Character.isWhitespace(trimmed.charAt(index))) {
                index++;
            }
            int start = index;
            while (index < trimmed.length() && !Character.isWhitespace(trimmed.charAt(index))) {
                index++;
            }
            if (seen == tokenIndex) {
                return trimmed.substring(index).trim();
            }
            seen++;
        }
        return "";
    }

    private static boolean isQualifiedName(String value) {
        return value != null && QUALIFIED_NAME.matcher(value).matches();
    }

    private static String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}

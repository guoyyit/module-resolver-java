package com.github.guoyyit.moduleresolverjava.parser;

import com.github.guoyyit.moduleresolverjava.model.ModuleDeclaration;
import com.github.guoyyit.moduleresolverjava.model.ExportDirective;
import com.github.guoyyit.moduleresolverjava.model.ProvideDirective;
import com.github.guoyyit.moduleresolverjava.model.RequireDirective;
import com.github.guoyyit.moduleresolverjava.model.SourceLocation;
import com.github.guoyyit.moduleresolverjava.model.UseDirective;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Parses the declaration grammar:
 * <pre>
 * module com.example.foo {
 *     requires [transitive|static] other.module;
 *     exports some.package;
 *     provides svc.Api with svc.impl;
 *     uses svc.Api;
 * }
 * </pre>
 */
public final class ModuleDeclarationParser {

    private static final Set<String> KEYWORDS =
            Set.of("module", "requires", "transitive", "static",
                    "exports", "provides", "with", "uses");

    public ModuleDeclaration parse(String content, String file) {
        List<ManifestLexer.Token> tokens = new ManifestLexer().tokenize(content, file);
        Cursor cursor = new Cursor(tokens, file);

        ManifestLexer.Token moduleKeyword = cursor.expect("module",
                "declaration must start with 'module'");
        ManifestLexer.Token nameToken = cursor.next();
        if (nameToken == null) {
            throw new ParseException("expected module name after 'module'",
                    cursor.endLocation());
        }
        requireQualifiedName(nameToken, "module name");
        String moduleName = nameToken.text();

        cursor.expect("{", "expected '{' after module name " + moduleName);

        List<RequireDirective> requires = new ArrayList<>();
        List<ExportDirective> exports = new ArrayList<>();
        List<ProvideDirective> provides = new ArrayList<>();
        List<UseDirective> uses = new ArrayList<>();

        while (true) {
            ManifestLexer.Token token = cursor.next();
            if (token == null) {
                throw new ParseException("unexpected end of file, expected directive or '}'",
                        cursor.endLocation());
            }
            if ("}".equals(token.text())) {
                break;
            }
            switch (token.text()) {
                case "requires" -> parseRequires(cursor, requires);
                case "exports" -> parseExports(cursor, exports);
                case "provides" -> parseProvides(cursor, provides);
                case "uses" -> parseUses(cursor, uses);
                default -> throw new ParseException(
                        "expected directive keyword (requires, exports, provides, uses) but found '"
                                + token.text() + "'", token.location());
            }
        }
        ManifestLexer.Token extra = cursor.next();
        if (extra != null) {
            throw new ParseException("unexpected token after closing '}': "
                    + extra.text(), extra.location());
        }
        return new ModuleDeclaration(moduleName, nameToken.location(), file,
                contentHash(content),
                List.copyOf(requires), List.copyOf(exports),
                List.copyOf(provides), List.copyOf(uses));
    }

    private static void parseRequires(Cursor cursor, List<RequireDirective> out) {
        boolean transitive = false;
        boolean optional = false;
        ManifestLexer.Token target = cursor.peek();
        while (target != null && ("transitive".equals(target.text())
                || "static".equals(target.text()))) {
            cursor.next();
            if ("transitive".equals(target.text())) {
                if (transitive) {
                    throw new ParseException("duplicate modifier 'transitive'",
                            target.location());
                }
                transitive = true;
            } else {
                if (optional) {
                    throw new ParseException("duplicate modifier 'static'",
                            target.location());
                }
                optional = true;
            }
            target = cursor.peek();
        }
        target = cursor.next();
        if (target == null) {
            throw new ParseException("expected module name after 'requires'",
                    cursor.endLocation());
        }
        requireQualifiedName(target, "required module name");
        cursor.expectSemicolon("requires " + target.text());
        out.add(new RequireDirective(target.text(), transitive, optional,
                target.location()));
    }

    private static void parseExports(Cursor cursor, List<ExportDirective> out) {
        ManifestLexer.Token packageName = cursor.next();
        if (packageName == null) {
            throw new ParseException("expected package name after 'exports'",
                    cursor.endLocation());
        }
        requireQualifiedName(packageName, "exported package name");
        cursor.expectSemicolon("exports " + packageName.text());
        out.add(new ExportDirective(packageName.text(), packageName.location()));
    }

    private static void parseProvides(Cursor cursor, List<ProvideDirective> out) {
        ManifestLexer.Token service = cursor.next();
        if (service == null) {
            throw new ParseException("expected service interface name after 'provides'",
                    cursor.endLocation());
        }
        requireQualifiedName(service, "service interface name");
        ManifestLexer.Token with = cursor.next();
        if (with == null || !"with".equals(with.text())) {
            throw new ParseException(
                    "expected 'with' after provides " + service.text(),
                    with == null ? cursor.endLocation() : with.location());
        }
        ManifestLexer.Token implementation = cursor.next();
        if (implementation == null) {
            throw new ParseException("expected implementation class name after 'with'",
                    cursor.endLocation());
        }
        requireQualifiedName(implementation, "implementation class name");
        cursor.expectSemicolon("provides " + service.text());
        out.add(new ProvideDirective(service.text(), implementation.text(),
                service.location()));
    }

    private static void parseUses(Cursor cursor, List<UseDirective> out) {
        ManifestLexer.Token service = cursor.next();
        if (service == null) {
            throw new ParseException("expected service interface name after 'uses'",
                    cursor.endLocation());
        }
        requireQualifiedName(service, "service interface name");
        cursor.expectSemicolon("uses " + service.text());
        out.add(new UseDirective(service.text(), service.location()));
    }

    private static void requireQualifiedName(ManifestLexer.Token token, String what) {
        String text = token.text();
        if (KEYWORDS.contains(text)) {
            throw new ParseException(what + " cannot be keyword '" + text + "'",
                    token.location());
        }
        String[] parts = text.split("\\.", -1);
        for (String part : parts) {
            if (part.isEmpty() || !isIdentifier(part)) {
                throw new ParseException("invalid " + what + " '" + text
                        + "': segments must be identifiers separated by '.'",
                        token.location());
            }
        }
    }

    private static boolean isIdentifier(String part) {
        char first = part.charAt(0);
        if (!Character.isJavaIdentifierStart(first)) {
            return false;
        }
        for (int i = 1; i < part.length(); i++) {
            if (!Character.isJavaIdentifierPart(part.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /** Small cursor over the token stream with accurate error locations. */
    private static final class Cursor {
        private final List<ManifestLexer.Token> tokens;
        private final String file;
        private int index;

        private Cursor(List<ManifestLexer.Token> tokens, String file) {
            this.tokens = tokens;
            this.file = file;
        }

        private ManifestLexer.Token peek() {
            return index < tokens.size() ? tokens.get(index) : null;
        }

        private ManifestLexer.Token next() {
            return index < tokens.size() ? tokens.get(index++) : null;
        }

        private ManifestLexer.Token expect(String expected, String message) {
            ManifestLexer.Token token = next();
            if (token == null || !expected.equals(token.text())) {
                throw new ParseException(message,
                        token == null ? endLocation() : token.location());
            }
            return token;
        }

        private void expectSemicolon(String directive) {
            ManifestLexer.Token token = next();
            if (token == null || !";".equals(token.text())) {
                throw new ParseException("missing ';' at end of directive '" + directive + "'",
                        token == null ? endLocation() : token.location());
            }
        }

        private SourceLocation endLocation() {
            if (tokens.isEmpty()) {
                return new SourceLocation(file, 1, 1);
            }
            ManifestLexer.Token last = tokens.get(tokens.size() - 1);
            SourceLocation location = last.location();
            return new SourceLocation(file, location.line(),
                    location.column() + Math.max(last.text().length() - 1, 0));
        }
    }

    /**
     * SHA-256 of the normalized file content, used by incremental
     * parsing to decide whether a declaration actually changed.
     */
    public static String contentHash(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}

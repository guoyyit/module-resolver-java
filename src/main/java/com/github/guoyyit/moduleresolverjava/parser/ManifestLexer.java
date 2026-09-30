package com.github.guoyyit.moduleresolverjava.parser;

import com.github.guoyyit.moduleresolverjava.model.SourceLocation;

import java.util.List;

/**
 * Lexer for module declaration files. Skips whitespace and line/block
 * comments ({@code //} and {@code /* *}{@code /}); every other run of
 * non-whitespace characters is a token carrying its start location.
 */
public final class ManifestLexer {

    public record Token(String text, SourceLocation location) {
    }

    public List<Token> tokenize(String content, String file) {
        List<Token> tokens = new java.util.ArrayList<>();
        int line = 1;
        int column = 1;
        int index = 0;
        int length = content.length();
        while (index < length) {
            char current = content.charAt(index);
            if (current == '\n') {
                index++;
                line++;
                column = 1;
            } else if (Character.isWhitespace(current)) {
                index++;
                column++;
            } else if (current == '/' && index + 1 < length
                    && content.charAt(index + 1) == '/') {
                index += 2;
                column += 2;
                while (index < length && content.charAt(index) != '\n') {
                    index++;
                    column++;
                }
            } else if (current == '/' && index + 1 < length
                    && content.charAt(index + 1) == '*') {
                SourceLocation start = new SourceLocation(file, line, column);
                index += 2;
                column += 2;
                boolean closed = false;
                while (index < length) {
                    char c = content.charAt(index);
                    if (c == '*' && index + 1 < length
                            && content.charAt(index + 1) == '/') {
                        index += 2;
                        column += 2;
                        closed = true;
                        break;
                    }
                    if (c == '\n') {
                        line++;
                        column = 1;
                    } else {
                        column++;
                    }
                    index++;
                }
                if (!closed) {
                    throw new ParseException("unterminated block comment", start);
                }
            } else if (current == '{' || current == '}' || current == ';') {
                SourceLocation start = new SourceLocation(file, line, column);
                tokens.add(new Token(String.valueOf(current), start));
                index++;
                column++;
            } else {
                SourceLocation start = new SourceLocation(file, line, column);
                int begin = index;
                while (index < length
                        && !Character.isWhitespace(content.charAt(index))
                        && content.charAt(index) != '{'
                        && content.charAt(index) != '}'
                        && content.charAt(index) != ';') {
                    index++;
                    column++;
                }
                tokens.add(new Token(content.substring(begin, index), start));
            }
        }
        return List.copyOf(tokens);
    }
}

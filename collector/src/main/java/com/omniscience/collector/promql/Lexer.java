package com.omniscience.collector.promql;

import java.util.ArrayList;
import java.util.List;

/**
 * Hand-written lexer for the PromQL subset.
 *
 * The one genuinely fiddly part is that {@code =} and {@code ==} mean different
 * things: inside {@code {...}} a single {@code =} is a label matcher, while at
 * expression level {@code ==} is a comparison. The lexer tracks brace depth so it
 * can emit MATCH_OP or COMPARE_OP correctly instead of leaving it to the parser.
 */
public final class Lexer {

    private final String src;
    private int pos;
    private int braceDepth;

    public Lexer(String src) {
        this.src = src;
    }

    public static List<Token> tokenize(String src) {
        return new Lexer(src).run();
    }

    public List<Token> run() {
        List<Token> out = new ArrayList<>();
        while (true) {
            skipWhitespace();
            if (pos >= src.length()) {
                out.add(new Token(Token.Type.EOF, "", pos));
                return out;
            }
            int start = pos;
            char c = src.charAt(pos);

            if (Character.isDigit(c) || (c == '.' && pos + 1 < src.length() && Character.isDigit(src.charAt(pos + 1)))) {
                out.add(readNumberOrDuration(start));
            } else if (Character.isLetter(c) || c == '_' || c == ':') {
                out.add(readIdentifier(start));
            } else if (c == '"' || c == '\'') {
                out.add(readString(start, c));
            } else {
                out.add(readOperatorOrPunct(start, c));
            }
        }
    }

    private void skipWhitespace() {
        while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
            pos++;
        }
    }

    private Token readNumberOrDuration(int start) {
        while (pos < src.length() && (Character.isDigit(src.charAt(pos)) || src.charAt(pos) == '.')) {
            pos++;
        }
        // A trailing unit makes it a duration: 5m, 30s, 1h, 2d, 100ms
        if (pos < src.length() && Character.isLetter(src.charAt(pos))) {
            int unitStart = pos;
            while (pos < src.length() && Character.isLetter(src.charAt(pos))) {
                pos++;
            }
            String unit = src.substring(unitStart, pos);
            if (unit.equals("ms") || unit.equals("s") || unit.equals("m")
                    || unit.equals("h") || unit.equals("d") || unit.equals("w")) {
                return new Token(Token.Type.DURATION, src.substring(start, pos), start);
            }
            throw new PromQLException("unknown duration unit '" + unit + "'", start);
        }
        return new Token(Token.Type.NUMBER, src.substring(start, pos), start);
    }

    /**
     * Identifiers allow '.' as well as '_' and ':'. Prometheus itself does not, but
     * our metric names are OTLP-style dotted ({@code system.cpu.load}), and the
     * query language has to speak the naming convention the platform actually uses.
     * Numbers are lexed by a separate branch, so a leading digit is never ambiguous.
     */
    private Token readIdentifier(int start) {
        while (pos < src.length()
                && (Character.isLetterOrDigit(src.charAt(pos)) || src.charAt(pos) == '_'
                    || src.charAt(pos) == ':' || src.charAt(pos) == '.')) {
            pos++;
        }
        return new Token(Token.Type.IDENT, src.substring(start, pos), start);
    }

    private Token readString(int start, char quote) {
        pos++; // opening quote
        StringBuilder sb = new StringBuilder();
        while (pos < src.length() && src.charAt(pos) != quote) {
            char ch = src.charAt(pos);
            if (ch == '\\' && pos + 1 < src.length()) {
                pos++;
                ch = src.charAt(pos);
            }
            sb.append(ch);
            pos++;
        }
        if (pos >= src.length()) {
            throw new PromQLException("unterminated string literal", start);
        }
        pos++; // closing quote
        return new Token(Token.Type.STRING, sb.toString(), start);
    }

    private Token readOperatorOrPunct(int start, char c) {
        switch (c) {
            case '(' -> {
                pos++;
                return new Token(Token.Type.LPAREN, "(", start);
            }
            case ')' -> {
                pos++;
                return new Token(Token.Type.RPAREN, ")", start);
            }
            case '{' -> {
                pos++;
                braceDepth++;
                return new Token(Token.Type.LBRACE, "{", start);
            }
            case '}' -> {
                pos++;
                braceDepth--;
                return new Token(Token.Type.RBRACE, "}", start);
            }
            case '[' -> {
                pos++;
                return new Token(Token.Type.LBRACKET, "[", start);
            }
            case ']' -> {
                pos++;
                return new Token(Token.Type.RBRACKET, "]", start);
            }
            case ',' -> {
                pos++;
                return new Token(Token.Type.COMMA, ",", start);
            }
            case '+', '-', '*', '/' -> {
                pos++;
                return new Token(Token.Type.ARITH_OP, String.valueOf(c), start);
            }
            case '=' -> {
                if (peekIs(1, '=')) {
                    pos += 2;
                    return new Token(Token.Type.COMPARE_OP, "==", start);
                }
                if (peekIs(1, '~')) {
                    pos += 2;
                    return new Token(Token.Type.MATCH_OP, "=~", start);
                }
                pos++;
                if (braceDepth <= 0) {
                    throw new PromQLException("bare '=' is only valid in a label matcher; use '==' to compare", start);
                }
                return new Token(Token.Type.MATCH_OP, "=", start);
            }
            case '!' -> {
                if (peekIs(1, '=')) {
                    pos += 2;
                    // Inside braces this negates a label; outside it compares.
                    return new Token(braceDepth > 0 ? Token.Type.MATCH_OP : Token.Type.COMPARE_OP, "!=", start);
                }
                if (peekIs(1, '~')) {
                    pos += 2;
                    return new Token(Token.Type.MATCH_OP, "!~", start);
                }
                throw new PromQLException("unexpected '!'", start);
            }
            case '>', '<' -> {
                if (peekIs(1, '=')) {
                    pos += 2;
                    return new Token(Token.Type.COMPARE_OP, c + "=", start);
                }
                pos++;
                return new Token(Token.Type.COMPARE_OP, String.valueOf(c), start);
            }
            default -> throw new PromQLException("unexpected character '" + c + "'", start);
        }
    }

    private boolean peekIs(int offset, char expected) {
        return pos + offset < src.length() && src.charAt(pos + offset) == expected;
    }
}

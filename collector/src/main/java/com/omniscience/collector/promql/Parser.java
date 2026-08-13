package com.omniscience.collector.promql;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Recursive-descent parser with precedence climbing.
 *
 * Precedence (loosest first): comparison, then + -, then * /, then unary, then
 * primaries. An alert rule must parse to a Comparison at the top — an expression
 * that merely produces a number is not a condition, and saying so at parse time
 * is far kinder than discovering it during an incident.
 */
public final class Parser {

    static final Set<String> AGGREGATORS = Set.of("sum", "avg", "min", "max", "count");
    static final Set<String> RANGE_FUNCTIONS = Set.of("rate", "increase");

    private final List<Token> tokens;
    private int index;

    public Parser(List<Token> tokens) {
        this.tokens = tokens;
    }

    /** Parses a complete alert condition and asserts nothing trails it. */
    public static Expr.Comparison parseCondition(String source) {
        Parser parser = new Parser(Lexer.tokenize(source));
        Expr expr = parser.parseComparison();
        parser.expect(Token.Type.EOF, "end of expression");
        if (!(expr instanceof Expr.Comparison comparison)) {
            throw new PromQLException(
                    "an alert rule must be a comparison, e.g. 'system.cpu.load > 0.9' — got an expression that yields a value");
        }
        return comparison;
    }

    /** Parses any expression (used by tests and by sub-expression parsing). */
    public static Expr parseExpression(String source) {
        Parser parser = new Parser(Lexer.tokenize(source));
        Expr expr = parser.parseComparison();
        parser.expect(Token.Type.EOF, "end of expression");
        return expr;
    }

    private Expr parseComparison() {
        Expr left = parseAdditive();
        if (peek().type() == Token.Type.COMPARE_OP) {
            Token op = advance();
            Expr right = parseAdditive();
            if (peek().type() == Token.Type.COMPARE_OP) {
                throw new PromQLException("chained comparisons are not supported", peek().position());
            }
            return new Expr.Comparison(op.text(), left, right);
        }
        return left;
    }

    private Expr parseAdditive() {
        Expr left = parseMultiplicative();
        while (peek().type() == Token.Type.ARITH_OP
                && (peek().text().equals("+") || peek().text().equals("-"))) {
            String op = advance().text();
            left = new Expr.Binary(op, left, parseMultiplicative());
        }
        return left;
    }

    private Expr parseMultiplicative() {
        Expr left = parseUnary();
        while (peek().type() == Token.Type.ARITH_OP
                && (peek().text().equals("*") || peek().text().equals("/"))) {
            String op = advance().text();
            left = new Expr.Binary(op, left, parseUnary());
        }
        return left;
    }

    private Expr parseUnary() {
        if (peek().type() == Token.Type.ARITH_OP && peek().text().equals("-")) {
            advance();
            return new Expr.Binary("-", new Expr.NumberLit(0), parseUnary());
        }
        return parsePrimary();
    }

    private Expr parsePrimary() {
        Token token = peek();
        switch (token.type()) {
            case NUMBER -> {
                advance();
                return new Expr.NumberLit(Double.parseDouble(token.text()));
            }
            case LPAREN -> {
                advance();
                Expr inner = parseComparison();
                expect(Token.Type.RPAREN, "')'");
                return inner;
            }
            case IDENT -> {
                return parseIdentifier();
            }
            default -> throw new PromQLException("unexpected " + token, token.position());
        }
    }

    private Expr parseIdentifier() {
        Token name = advance();
        if (peek().type() == Token.Type.LPAREN) {
            String fn = name.text();
            if (!AGGREGATORS.contains(fn) && !RANGE_FUNCTIONS.contains(fn)) {
                throw new PromQLException("unsupported function '" + fn + "' — this subset supports "
                        + sorted(AGGREGATORS) + " and " + sorted(RANGE_FUNCTIONS), name.position());
            }
            advance();
            Expr arg = parseComparison();
            expect(Token.Type.RPAREN, "')' to close " + fn + "(");
            if (RANGE_FUNCTIONS.contains(fn)
                    && !(arg instanceof Expr.Selector s && s.range() != null)) {
                throw new PromQLException(fn + "() requires a range selector, e.g. " + fn
                        + "(http_requests_total[5m])", name.position());
            }
            return new Expr.FuncCall(fn, arg);
        }
        return parseSelector(name);
    }

    private Expr.Selector parseSelector(Token metric) {
        List<Expr.Matcher> matchers = new ArrayList<>();
        if (peek().type() == Token.Type.LBRACE) {
            advance();
            while (peek().type() != Token.Type.RBRACE) {
                Token label = expect(Token.Type.IDENT, "a label name");
                Token op = expect(Token.Type.MATCH_OP, "one of = != =~ !~");
                Token value = expect(Token.Type.STRING, "a quoted label value");
                matchers.add(new Expr.Matcher(label.text(), op.text(), value.text()));
                if (peek().type() == Token.Type.COMMA) {
                    advance();
                } else {
                    break;
                }
            }
            expect(Token.Type.RBRACE, "'}'");
        }

        Duration range = null;
        if (peek().type() == Token.Type.LBRACKET) {
            advance();
            Token duration = expect(Token.Type.DURATION, "a duration such as 5m");
            range = parseDuration(duration.text(), duration.position());
            expect(Token.Type.RBRACKET, "']'");
        }
        return new Expr.Selector(metric.text(), List.copyOf(matchers), range);
    }

    static Duration parseDuration(String text, int position) {
        int split = 0;
        while (split < text.length() && (Character.isDigit(text.charAt(split)) || text.charAt(split) == '.')) {
            split++;
        }
        long amount = (long) Double.parseDouble(text.substring(0, split));
        String unit = text.substring(split);
        return switch (unit) {
            case "ms" -> Duration.ofMillis(amount);
            case "s" -> Duration.ofSeconds(amount);
            case "m" -> Duration.ofMinutes(amount);
            case "h" -> Duration.ofHours(amount);
            case "d" -> Duration.ofDays(amount);
            case "w" -> Duration.ofDays(amount * 7);
            default -> throw new PromQLException("unknown duration unit '" + unit + "'", position);
        };
    }

    private static String sorted(Set<String> values) {
        return values.stream().sorted().toList().toString();
    }

    private Token peek() {
        return tokens.get(index);
    }

    private Token advance() {
        return tokens.get(index++);
    }

    private Token expect(Token.Type type, String description) {
        Token token = peek();
        if (token.type() != type) {
            throw new PromQLException("expected " + description + " but found "
                    + (token.type() == Token.Type.EOF ? "end of expression" : "'" + token.text() + "'"),
                    token.position());
        }
        return advance();
    }
}

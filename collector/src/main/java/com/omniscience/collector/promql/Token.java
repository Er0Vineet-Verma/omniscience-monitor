package com.omniscience.collector.promql;

public record Token(Token.Type type, String text, int position) {

    public enum Type {
        IDENT, NUMBER, STRING, DURATION,
        LPAREN, RPAREN, LBRACE, RBRACE, LBRACKET, RBRACKET, COMMA,
        MATCH_OP,    // = != =~ !~   (only legal inside a label matcher)
        COMPARE_OP,  // > >= < <= == !=
        ARITH_OP,    // + - * /
        EOF
    }

    @Override
    public String toString() {
        return type + "('" + text + "')";
    }
}

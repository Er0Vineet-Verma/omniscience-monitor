package com.omniscience.collector.promql;

import java.time.Duration;
import java.util.List;

/** AST for the PromQL subset. Sealed so the evaluator's switch is exhaustive. */
public sealed interface Expr {

    record NumberLit(double value) implements Expr {
    }

    /** {@code metric{label="v"}} — {@code range} non-null makes it a range vector, legal only inside rate()/increase(). */
    record Selector(String metric, List<Matcher> matchers, Duration range) implements Expr {
    }

    record FuncCall(String name, Expr arg) implements Expr {
    }

    /** Arithmetic: + - * / */
    record Binary(String op, Expr left, Expr right) implements Expr {
    }

    /** Top-level condition: > >= < <= == != */
    record Comparison(String op, Expr left, Expr right) implements Expr {
    }

    /** A label matcher. {@code op} is one of = != =~ !~ */
    record Matcher(String label, String op, String value) {
    }
}

package com.omniscience.collector.promql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class ParserTest {

    @Test
    void parsesSimpleThreshold() {
        Expr.Comparison c = Parser.parseCondition("system.cpu.load > 0.9");
        assertEquals(">", c.op());
        Expr.Selector s = assertInstanceOf(Expr.Selector.class, c.left());
        assertEquals("system.cpu.load", s.metric());
        assertTrue(s.matchers().isEmpty());
        assertEquals(0.9, assertInstanceOf(Expr.NumberLit.class, c.right()).value());
    }

    @Test
    void parsesLabelMatchersIncludingRegex() {
        Expr.Comparison c = Parser.parseCondition(
                "http_requests_total{status=~\"5..\", method!=\"GET\"} > 10");
        Expr.Selector s = assertInstanceOf(Expr.Selector.class, c.left());
        assertEquals(2, s.matchers().size());
        assertEquals(new Expr.Matcher("status", "=~", "5.."), s.matchers().get(0));
        assertEquals(new Expr.Matcher("method", "!=", "GET"), s.matchers().get(1));
    }

    @Test
    void parsesRangeSelectorAndDuration() {
        Expr.Comparison c = Parser.parseCondition("rate(http_requests_total[5m]) > 1");
        Expr.FuncCall f = assertInstanceOf(Expr.FuncCall.class, c.left());
        assertEquals("rate", f.name());
        assertEquals(Duration.ofMinutes(5), assertInstanceOf(Expr.Selector.class, f.arg()).range());
    }

    /** The error-rate expression from the Alerts Feed design. */
    @Test
    void parsesTheDesignsErrorRatioExpression() {
        Expr.Comparison c = Parser.parseCondition(
                "sum(rate(http_requests_total{status=~\"5..\"}[5m])) / sum(rate(http_requests_total[5m])) > 0.05");
        assertEquals(">", c.op());
        Expr.Binary div = assertInstanceOf(Expr.Binary.class, c.left());
        assertEquals("/", div.op());
        assertEquals("sum", assertInstanceOf(Expr.FuncCall.class, div.left()).name());
        assertEquals("sum", assertInstanceOf(Expr.FuncCall.class, div.right()).name());
    }

    @Test
    void multiplicationBindsTighterThanAddition() {
        Expr expr = Parser.parseExpression("1 + 2 * 3");
        Expr.Binary add = assertInstanceOf(Expr.Binary.class, expr);
        assertEquals("+", add.op());
        assertEquals("*", assertInstanceOf(Expr.Binary.class, add.right()).op());
    }

    @Test
    void parenthesesOverridePrecedence() {
        Expr.Binary mul = assertInstanceOf(Expr.Binary.class, Parser.parseExpression("(1 + 2) * 3"));
        assertEquals("*", mul.op());
        assertEquals("+", assertInstanceOf(Expr.Binary.class, mul.left()).op());
    }

    @Test
    void rejectsExpressionThatIsNotAComparison() {
        PromQLException e = assertThrows(PromQLException.class,
                () -> Parser.parseCondition("sum(system.cpu.load)"));
        assertTrue(e.getMessage().contains("must be a comparison"));
    }

    @Test
    void rejectsUnsupportedFunctionByName() {
        PromQLException e = assertThrows(PromQLException.class,
                () -> Parser.parseCondition("histogram_quantile(0.9, foo) > 1"));
        assertTrue(e.getMessage().contains("unsupported function 'histogram_quantile'"));
    }

    @Test
    void rateRequiresARangeSelector() {
        PromQLException e = assertThrows(PromQLException.class,
                () -> Parser.parseCondition("rate(http_requests_total) > 1"));
        assertTrue(e.getMessage().contains("requires a range selector"));
    }

    @Test
    void reportsPositionOnUnexpectedInput() {
        PromQLException e = assertThrows(PromQLException.class,
                () -> Parser.parseCondition("system.cpu.load > "));
        assertTrue(e.getMessage().contains("position"));
    }

    @Test
    void rejectsChainedComparisons() {
        assertThrows(PromQLException.class, () -> Parser.parseCondition("1 < system.cpu.load < 2"));
    }

    @Test
    void bareEqualsOutsideMatcherIsRejectedWithGuidance() {
        PromQLException e = assertThrows(PromQLException.class,
                () -> Parser.parseCondition("system.cpu.load = 0.9"));
        assertTrue(e.getMessage().contains("use '=='"));
    }

    @Test
    void unterminatedStringIsReported() {
        assertThrows(PromQLException.class, () -> Parser.parseCondition("foo{a=\"b} > 1"));
    }
}

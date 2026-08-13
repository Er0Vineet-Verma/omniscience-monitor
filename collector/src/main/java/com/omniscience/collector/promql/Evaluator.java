package com.omniscience.collector.promql;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import com.omniscience.collector.store.MetricStore.RawPoint;

/**
 * Evaluates a parsed condition for one tenant + host at one instant.
 *
 * Deliberate simplifications versus real PromQL, all of them explicit rather than
 * accidental:
 * - an instant selector takes the most recent sample inside the lookback window;
 * - a vector reaching a scalar position collapses only if it holds one series,
 *   otherwise evaluation fails telling the author to aggregate. Silently picking
 *   one of several series is how alerts come to mean something nobody intended;
 * - no data yields {@code empty}, which the caller treats as "not violating"
 *   rather than as a breach. Absence of signal is not evidence of failure.
 */
public final class Evaluator {

    /** Supplies series and samples; implemented over MetricStore, faked in tests. */
    public interface DataSource {

        List<SeriesRef> findSeries(String orgId, String metric);

        List<RawPoint> samples(List<Long> seriesIds, Instant from, Instant to);

        record SeriesRef(long id, Map<String, String> tags) {
        }
    }

    private final DataSource data;
    private final Duration lookback;

    public Evaluator(DataSource data, Duration lookback) {
        this.data = data;
        this.lookback = lookback;
    }

    /** Empty means "no data" — never treated as a violation. */
    public Optional<Boolean> matches(Expr.Comparison condition, String orgId, String host, Instant now) {
        Value left = eval(condition.left(), orgId, host, now);
        Value right = eval(condition.right(), orgId, host, now);
        Optional<Double> l = collapse(left);
        Optional<Double> r = collapse(right);
        if (l.isEmpty() || r.isEmpty() || l.get().isNaN() || r.get().isNaN()) {
            return Optional.empty();
        }
        return Optional.of(compare(condition.op(), l.get(), r.get()));
    }

    /** The scalar the condition's left side currently evaluates to — used for incident context. */
    public Optional<Double> leftValue(Expr.Comparison condition, String orgId, String host, Instant now) {
        return collapse(eval(condition.left(), orgId, host, now)).filter(d -> !d.isNaN());
    }

    Value eval(Expr expr, String orgId, String host, Instant now) {
        return switch (expr) {
            case Expr.NumberLit n -> new Value.Scalar(n.value());
            case Expr.Selector s -> evalSelector(s, orgId, host, now);
            case Expr.FuncCall f -> evalFunction(f, orgId, host, now);
            case Expr.Binary b -> evalBinary(b, orgId, host, now);
            case Expr.Comparison c -> new Value.Scalar(
                    matches(c, orgId, host, now).map(v -> v ? 1.0 : 0.0).orElse(Double.NaN));
        };
    }

    private Value evalSelector(Expr.Selector selector, String orgId, String host, Instant now) {
        Duration window = selector.range() != null ? selector.range() : lookback;
        Instant from = now.minus(window);

        List<DataSource.SeriesRef> refs = data.findSeries(orgId, selector.metric()).stream()
                .filter(ref -> host == null || host.equals(ref.tags().get("host")))
                .filter(ref -> matchesAll(ref.tags(), selector.matchers()))
                .toList();
        if (refs.isEmpty()) {
            return selector.range() != null ? new Value.Range(List.of()) : new Value.Vector(List.of());
        }

        List<RawPoint> points = data.samples(refs.stream().map(DataSource.SeriesRef::id).toList(), from, now);

        if (selector.range() != null) {
            List<Value.RangeElement> elements = new ArrayList<>();
            for (DataSource.SeriesRef ref : refs) {
                List<RawPoint> own = points.stream()
                        .filter(p -> p.seriesId() == ref.id())
                        .sorted(Comparator.comparing(RawPoint::ts))
                        .toList();
                if (!own.isEmpty()) {
                    elements.add(new Value.RangeElement(ref.id(), ref.tags(), own));
                }
            }
            return new Value.Range(elements);
        }

        List<Value.Element> elements = new ArrayList<>();
        for (DataSource.SeriesRef ref : refs) {
            points.stream()
                    .filter(p -> p.seriesId() == ref.id())
                    .max(Comparator.comparing(RawPoint::ts))
                    .ifPresent(latest -> elements.add(
                            new Value.Element(ref.id(), ref.tags(), latest.value())));
        }
        return new Value.Vector(elements);
    }

    private Value evalFunction(Expr.FuncCall call, String orgId, String host, Instant now) {
        Value arg = eval(call.arg(), orgId, host, now);
        if (Parser.RANGE_FUNCTIONS.contains(call.name())) {
            if (!(arg instanceof Value.Range range)) {
                throw new PromQLException(call.name() + "() requires a range selector");
            }
            List<Value.Element> out = new ArrayList<>();
            for (Value.RangeElement element : range.elements()) {
                double increase = counterIncrease(element.points());
                double value = call.name().equals("rate")
                        ? increase / windowSeconds(call, element)
                        : increase;
                out.add(new Value.Element(element.seriesId(), element.tags(), value));
            }
            return new Value.Vector(out);
        }

        List<Double> values = switch (arg) {
            case Value.Vector v -> v.elements().stream().map(Value.Element::value).toList();
            case Value.Scalar s -> List.of(s.value());
            case Value.Range r -> throw new PromQLException(
                    call.name() + "() cannot aggregate a range selector directly — wrap it in rate() first");
        };
        if (values.isEmpty()) {
            return new Value.Scalar(Double.NaN);
        }
        double result = switch (call.name()) {
            case "sum" -> values.stream().mapToDouble(Double::doubleValue).sum();
            case "avg" -> values.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
            case "min" -> values.stream().mapToDouble(Double::doubleValue).min().orElse(Double.NaN);
            case "max" -> values.stream().mapToDouble(Double::doubleValue).max().orElse(Double.NaN);
            case "count" -> values.size();
            default -> throw new PromQLException("unsupported function '" + call.name() + "'");
        };
        return new Value.Scalar(result);
    }

    private double windowSeconds(Expr.FuncCall call, Value.RangeElement element) {
        if (call.arg() instanceof Expr.Selector s && s.range() != null) {
            return s.range().toMillis() / 1000.0;
        }
        List<RawPoint> points = element.points();
        double span = (points.get(points.size() - 1).ts().toEpochMilli() - points.get(0).ts().toEpochMilli()) / 1000.0;
        return span > 0 ? span : 1;
    }

    /**
     * Total increase across the window, tolerating counter resets: a negative step
     * means the counter restarted, so the post-reset value is the increase.
     */
    private static double counterIncrease(List<RawPoint> points) {
        double total = 0;
        for (int i = 1; i < points.size(); i++) {
            double delta = points.get(i).value() - points.get(i - 1).value();
            total += delta >= 0 ? delta : points.get(i).value();
        }
        return total;
    }

    private Value evalBinary(Expr.Binary binary, String orgId, String host, Instant now) {
        Optional<Double> l = collapse(eval(binary.left(), orgId, host, now));
        Optional<Double> r = collapse(eval(binary.right(), orgId, host, now));
        if (l.isEmpty() || r.isEmpty()) {
            return new Value.Scalar(Double.NaN);
        }
        double a = l.get();
        double b = r.get();
        double result = switch (binary.op()) {
            case "+" -> a + b;
            case "-" -> a - b;
            case "*" -> a * b;
            // Division by zero yields NaN, which propagates to "no decision" rather
            // than Infinity, which would compare as a breach against any threshold.
            case "/" -> b == 0 ? Double.NaN : a / b;
            default -> throw new PromQLException("unsupported operator '" + binary.op() + "'");
        };
        return new Value.Scalar(result);
    }

    private static Optional<Double> collapse(Value value) {
        return switch (value) {
            case Value.Scalar s -> Optional.of(s.value());
            case Value.Vector v -> switch (v.elements().size()) {
                case 0 -> Optional.empty();
                case 1 -> Optional.of(v.elements().get(0).value());
                default -> throw new PromQLException("expression yields " + v.elements().size()
                        + " series; wrap it in sum(), avg(), min() or max() to get a single value");
            };
            case Value.Range r -> throw new PromQLException(
                    "a range selector must be wrapped in rate() or increase()");
        };
    }

    private static boolean matchesAll(Map<String, String> tags, List<Expr.Matcher> matchers) {
        for (Expr.Matcher m : matchers) {
            String actual = tags.getOrDefault(m.label(), "");
            boolean ok = switch (m.op()) {
                case "=" -> actual.equals(m.value());
                case "!=" -> !actual.equals(m.value());
                case "=~" -> regex(m.value()).matcher(actual).matches();
                case "!~" -> !regex(m.value()).matcher(actual).matches();
                default -> throw new PromQLException("unsupported matcher '" + m.op() + "'");
            };
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    private static Pattern regex(String pattern) {
        try {
            // Prometheus anchors regex matchers fully; matches() gives the same behaviour.
            return Pattern.compile(pattern);
        } catch (PatternSyntaxException e) {
            throw new PromQLException("invalid regex '" + pattern + "': " + e.getDescription());
        }
    }

    private static boolean compare(String op, double left, double right) {
        return switch (op) {
            case ">" -> left > right;
            case ">=" -> left >= right;
            case "<" -> left < right;
            case "<=" -> left <= right;
            case "==" -> left == right;
            case "!=" -> left != right;
            default -> throw new PromQLException("unsupported comparison '" + op + "'");
        };
    }
}

package com.omniscience.collector.promql;

import java.util.List;
import java.util.Map;

import com.omniscience.collector.store.MetricStore.RawPoint;

/** Evaluation results. Mirrors PromQL's scalar / instant-vector / range-vector split. */
public sealed interface Value {

    record Scalar(double value) implements Value {
    }

    record Vector(List<Element> elements) implements Value {
    }

    record Range(List<RangeElement> elements) implements Value {
    }

    record Element(long seriesId, Map<String, String> tags, double value) {
    }

    record RangeElement(long seriesId, Map<String, String> tags, List<RawPoint> points) {
    }
}

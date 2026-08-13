package com.omniscience.collector.promql;

/**
 * Raised for anything the subset cannot honour. Unsupported syntax fails loudly
 * with a position and a reason rather than silently evaluating to something
 * plausible — a rule that quietly means the wrong thing is worse than one that
 * refuses to save.
 */
public class PromQLException extends RuntimeException {

    public PromQLException(String message) {
        super(message);
    }

    public PromQLException(String message, int position) {
        super(message + " (at position " + position + ")");
    }
}

package com.lifeos.algorithms;

/**
 * Signals a directed cycle in a graph that requires a complete dependency-respecting order.
 *
 * <p>The exception deliberately carries no caller node values: callers can render a bounded,
 * domain-authorized explanation from their own trusted graph projection instead.
 */
public class AlgorithmCycleException extends IllegalArgumentException {

    public AlgorithmCycleException() {
        super("A dependency cycle prevents a complete execution order");
    }
}

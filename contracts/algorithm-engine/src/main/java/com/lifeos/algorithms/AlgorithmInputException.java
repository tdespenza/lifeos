package com.lifeos.algorithms;

/**
 * Signals malformed or resource-unbounded input before an algorithm starts returning a partial
 * result. The message intentionally omits caller-provided values so it is safe at service edges.
 */
public class AlgorithmInputException extends IllegalArgumentException {

    public AlgorithmInputException(String message) {
        super(message);
    }
}

package com.lifeos.taskgoal.observability;

import java.lang.ScopedValue;

/**
 * Request-scoped values shared by task/goal components.
 */
public final class RequestContext {

    /**
     * Correlation identifier bound during request processing.
     */
    public static final ScopedValue<String> CORRELATION_ID = ScopedValue.newInstance();

    private RequestContext() {
    }
}

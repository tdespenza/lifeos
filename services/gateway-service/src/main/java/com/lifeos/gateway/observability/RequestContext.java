package com.lifeos.gateway.observability;

import java.lang.ScopedValue;

/**
 * Request-scoped values shared by gateway components without mutable global state.
 */
public final class RequestContext {

    /**
     * Correlation identifier bound during request processing.
     */
    public static final ScopedValue<String> CORRELATION_ID = ScopedValue.newInstance();

    private RequestContext() {
    }
}

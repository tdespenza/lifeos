package com.lifeos.identity.observability;

import java.lang.ScopedValue;

/**
 * Request-scoped values shared by identity-service components without global mutable state.
 */
public final class RequestContext {

    /**
     * Correlation identifier bound while a request is being processed.
     */
    public static final ScopedValue<String> CORRELATION_ID = ScopedValue.newInstance();

    private RequestContext() {
    }
}

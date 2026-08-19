package com.lifeos.trustledger.observability;

import java.lang.ScopedValue;

/** Request-scoped context propagated to outbound Identity calls. */
public final class RequestContext {

    public static final ScopedValue<String> CORRELATION_ID = ScopedValue.newInstance();

    private RequestContext() {
    }
}

package com.lifeos.profile.observability;

import java.lang.ScopedValue;

/** Request-scoped values shared without mutable thread-local state. */
public final class RequestContext {

    public static final ScopedValue<String> CORRELATION_ID = ScopedValue.newInstance();
    public static final ScopedValue<String> CLIENT_ADDRESS = ScopedValue.newInstance();

    private RequestContext() {
    }
}

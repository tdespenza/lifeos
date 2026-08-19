package com.lifeos.media.observability;

/** Scoped request facts propagated to audit records and bounded Identity calls. */
public final class RequestContext {

    public static final ScopedValue<String> CORRELATION_ID = ScopedValue.newInstance();
    public static final ScopedValue<String> CLIENT_ADDRESS = ScopedValue.newInstance();

    private RequestContext() {
    }
}

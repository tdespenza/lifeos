package com.lifeos.documentvault.observability;

/** Scoped request facts for outbound identity calls and structured audit logs. */
public final class RequestContext {

    public static final ScopedValue<String> CORRELATION_ID = ScopedValue.newInstance();
    public static final ScopedValue<String> CLIENT_ADDRESS = ScopedValue.newInstance();

    private RequestContext() {
    }
}

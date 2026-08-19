package com.lifeos.notification.observability;

/** Scoped request values propagated to safe outbound identity calls. */
public final class RequestContext {

    public static final ScopedValue<String> CORRELATION_ID = ScopedValue.newInstance();

    private RequestContext() {
    }
}

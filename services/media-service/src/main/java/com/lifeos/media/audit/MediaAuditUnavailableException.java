package com.lifeos.media.audit;

/** Audit persistence failed; security-sensitive operation results must not be silently lost. */
public class MediaAuditUnavailableException extends RuntimeException {

    public MediaAuditUnavailableException(Throwable cause) {
        super(cause);
    }
}

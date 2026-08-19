package com.lifeos.media.authorization;

/** Caller bearer was absent or rejected by Identity. */
public class MediaAuthenticationFailure extends RuntimeException {

    public MediaAuthenticationFailure() {
    }

    public MediaAuthenticationFailure(Throwable cause) {
        super(cause);
    }
}

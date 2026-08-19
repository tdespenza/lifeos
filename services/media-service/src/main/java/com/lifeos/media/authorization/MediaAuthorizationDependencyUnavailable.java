package com.lifeos.media.authorization;

/** Identity validation/decision could not be completed safely within configured bounds. */
public class MediaAuthorizationDependencyUnavailable extends RuntimeException {

    public MediaAuthorizationDependencyUnavailable() {
    }

    public MediaAuthorizationDependencyUnavailable(Throwable cause) {
        super(cause);
    }
}

package com.lifeos.profile.authorization;

/** Raised when Identity cannot safely validate a subject or return a usable decision. */
public class ProfileAuthorizationDependencyUnavailable extends RuntimeException {

    public ProfileAuthorizationDependencyUnavailable() {
        super();
    }

    public ProfileAuthorizationDependencyUnavailable(Throwable cause) {
        super(cause);
    }
}

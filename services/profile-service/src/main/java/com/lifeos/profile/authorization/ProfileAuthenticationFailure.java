package com.lifeos.profile.authorization;

/** Raised when an inbound bearer credential is absent or Identity rejects it. */
public class ProfileAuthenticationFailure extends RuntimeException {

    public ProfileAuthenticationFailure() {
        super();
    }

    public ProfileAuthenticationFailure(Throwable cause) {
        super(cause);
    }
}

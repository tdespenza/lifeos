package com.lifeos.notification.security;

/** A persisted endpoint destination cannot safely be decrypted with the configured key. */
public class EndpointCipherException extends RuntimeException {

    public EndpointCipherException(String message, Throwable cause) {
        super(message, cause);
    }

    public EndpointCipherException(String message) {
        super(message);
    }
}

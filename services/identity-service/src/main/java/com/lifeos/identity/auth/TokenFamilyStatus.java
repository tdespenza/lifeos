package com.lifeos.identity.auth;

/** Lifecycle state of a refresh-token family. */
public enum TokenFamilyStatus {
    ACTIVE,
    REVOKED,
    EXPIRED
}

package com.lifeos.identity.account;

/**
 * Lifecycle state used by the identity service to decide whether an account may authenticate.
 */
public enum AccountStatus {

    /** Account may authenticate and create sessions. */
    ACTIVE,

    /** Account is retained but cannot authenticate until re-enabled. */
    DISABLED
}

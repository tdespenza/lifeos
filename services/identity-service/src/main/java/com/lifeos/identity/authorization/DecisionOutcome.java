package com.lifeos.identity.authorization;

/** Outcome returned by the authorization authority. */
public enum DecisionOutcome {

    /** The subject may perform the requested action on the supplied trusted facts. */
    ALLOW,

    /** The subject may not perform the requested action. */
    DENY
}

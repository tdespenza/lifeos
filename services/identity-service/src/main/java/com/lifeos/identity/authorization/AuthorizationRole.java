package com.lifeos.identity.authorization;

/** Roles supported by the initial deterministic authorization policy. */
public enum AuthorizationRole {

    /** Baseline role for a subject's own personal tenant. */
    MEMBER,

    /** Explicitly granted role for administration within one tenant scope. */
    TENANT_ADMIN
}

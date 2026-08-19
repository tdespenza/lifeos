package com.lifeos.identity.authorization;

/** Tenant boundary that a descriptor applies after RBAC has selected scoped roles. */
public enum AuthorizationTenantScope {

    /** The resource tenant must be the verified subject's personal account tenant. */
    PERSONAL,

    /** An explicit active membership may supply the resource tenant. */
    SCOPED
}

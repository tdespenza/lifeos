package com.lifeos.trustledger.access;

import java.util.Map;
import java.util.Objects;

/**
 * Trusted personal-tenant collection capability used for bounded stateless proof operations.
 *
 * <p>No caller-supplied document or account fact is copied into the authorization payload. The
 * exact Identity V2 descriptors require an empty attribute map for this resource family.
 */
public record TrustAuthorizationResource(String resourceType, String resourceId, String tenantId, Map<String, String> attributes) {

    private static final String RESOURCE_TYPE = "trust-ledger";

    public TrustAuthorizationResource {
        if (!RESOURCE_TYPE.equals(resourceType)) {
            throw new IllegalArgumentException("unsupported trust authorization resource type");
        }
        if (resourceId == null || resourceId.isBlank() || tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("resourceId and tenantId must not be blank");
        }
        attributes = Map.copyOf(Objects.requireNonNull(attributes, "attributes must not be null"));
        if (!attributes.isEmpty()) {
            throw new IllegalArgumentException("trust-ledger resource attributes must be empty");
        }
    }

    public static TrustAuthorizationResource forSubject(TrustSubject subject) {
        Objects.requireNonNull(subject, "subject must not be null");
        return new TrustAuthorizationResource(
                RESOURCE_TYPE, subject.accountId().toString(), subject.tenantId(), Map.of());
    }
}

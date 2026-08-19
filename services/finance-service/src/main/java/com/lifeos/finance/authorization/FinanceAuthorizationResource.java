package com.lifeos.finance.authorization;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Trusted V2 Identity resource facts, constructed from the authenticated subject and locally
 * loaded records only. JSON input never supplies an owner, tenant, or resource-existence fact.
 */
public record FinanceAuthorizationResource(
        String resourceType, String resourceId, String tenantId, Map<String, String> attributes) {

    private static final Set<String> OWNED_TYPES =
            Set.of("finance-budget", "finance-transaction", "finance-goal");
    private static final String COLLECTION_TYPE = "finance";
    private static final String OWNER_ACCOUNT_ID = "ownerAccountId";
    private static final String RESOURCE_EXISTS = "resourceExists";
    private static final String MISSING_OWNER = "00000000-0000-0000-0000-000000000000";

    public FinanceAuthorizationResource {
        if (!(OWNED_TYPES.contains(resourceType) || COLLECTION_TYPE.equals(resourceType))) {
            throw new IllegalArgumentException("unsupported finance authorization resource type");
        }
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        attributes = Map.copyOf(Objects.requireNonNull(attributes, "attributes must not be null"));
        boolean collection = resourceId == null && attributes.isEmpty();
        if (collection) {
            // Each exact family has a distinct Identity descriptor but shares the V2 empty
            // collection shape. `finance` is reserved for insights and forecast.
        } else if (COLLECTION_TYPE.equals(resourceType)) {
            throw new IllegalArgumentException("finance collection resources cannot contain facts");
        } else {
            requireUuid(resourceId, "resourceId");
            validateOwnedFacts(attributes);
        }
    }

    public static FinanceAuthorizationResource forNew(FinanceSubject subject, String type, UUID id) {
        return new FinanceAuthorizationResource(
                type,
                Objects.requireNonNull(id, "id must not be null").toString(),
                subject.tenantId(),
                Map.of(OWNER_ACCOUNT_ID, subject.accountId().toString()));
    }

    public static FinanceAuthorizationResource forExisting(
            FinanceSubject subject, String type, UUID id, UUID ownerAccountId) {
        return new FinanceAuthorizationResource(
                type,
                Objects.requireNonNull(id, "id must not be null").toString(),
                subject.tenantId(),
                Map.of(
                        OWNER_ACCOUNT_ID,
                        Objects.requireNonNull(ownerAccountId, "ownerAccountId must not be null").toString(),
                        RESOURCE_EXISTS,
                        "true"));
    }

    public static FinanceAuthorizationResource forMissing(FinanceSubject subject, String type, UUID id) {
        return new FinanceAuthorizationResource(
                type,
                Objects.requireNonNull(id, "id must not be null").toString(),
                subject.tenantId(),
                Map.of(OWNER_ACCOUNT_ID, MISSING_OWNER, RESOURCE_EXISTS, "false"));
    }

    /** Builds the empty V2 collection shape for one exact Finance resource family. */
    public static FinanceAuthorizationResource forCollection(FinanceSubject subject, String resourceType) {
        if (!(OWNED_TYPES.contains(resourceType) || COLLECTION_TYPE.equals(resourceType))) {
            throw new IllegalArgumentException("unsupported finance authorization collection type");
        }
        return new FinanceAuthorizationResource(resourceType, null, subject.tenantId(), Map.of());
    }

    private static void validateOwnedFacts(Map<String, String> attributes) {
        if (!attributes.keySet().equals(Set.of(OWNER_ACCOUNT_ID))
                && !attributes.keySet().equals(Set.of(OWNER_ACCOUNT_ID, RESOURCE_EXISTS))) {
            throw new IllegalArgumentException("finance resource facts have an unsupported shape");
        }
        requireUuid(attributes.get(OWNER_ACCOUNT_ID), OWNER_ACCOUNT_ID);
        if (attributes.containsKey(RESOURCE_EXISTS)
                && !("true".equals(attributes.get(RESOURCE_EXISTS)) || "false".equals(attributes.get(RESOURCE_EXISTS)))) {
            throw new IllegalArgumentException("resourceExists must be true or false");
        }
    }

    private static void requireUuid(String value, String name) {
        try {
            UUID.fromString(value);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new IllegalArgumentException(name + " must be a UUID", exception);
        }
    }
}

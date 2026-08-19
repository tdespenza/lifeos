package com.lifeos.documentvault.authorization;

import com.lifeos.documentvault.domain.VaultDocument;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Trusted local document facts sent to Identity; callers never supply this shape. */
public record DocumentVaultAuthorizationResource(
        String resourceType, String resourceId, String tenantId, Map<String, String> attributes) {

    private static final String RESOURCE_TYPE = "document";
    private static final String OWNER_ACCOUNT_ID = "ownerAccountId";
    private static final String RESOURCE_EXISTS = "resourceExists";
    private static final String MISSING_OWNER_ACCOUNT_ID = "00000000-0000-0000-0000-000000000000";

    public DocumentVaultAuthorizationResource {
        if (!RESOURCE_TYPE.equals(resourceType)) {
            throw new IllegalArgumentException("resourceType must be document");
        }
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        attributes = Map.copyOf(Objects.requireNonNull(attributes, "attributes must not be null"));
        if (resourceId == null) {
            if (!attributes.isEmpty()) {
                throw new IllegalArgumentException("collection resources must not contain attributes");
            }
        } else {
            try {
                UUID.fromString(resourceId);
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("resourceId must be a UUID", exception);
            }
            if (attributes.get(OWNER_ACCOUNT_ID) == null || attributes.get(OWNER_ACCOUNT_ID).isBlank()) {
                throw new IllegalArgumentException("ownerAccountId must not be blank");
            }
            if (!attributes.keySet().equals(Set.of(OWNER_ACCOUNT_ID))
                    && !attributes.keySet().equals(Set.of(OWNER_ACCOUNT_ID, RESOURCE_EXISTS))) {
                throw new IllegalArgumentException("document resources contain unsupported attributes");
            }
            if (attributes.containsKey(RESOURCE_EXISTS)
                    && !("true".equals(attributes.get(RESOURCE_EXISTS))
                            || "false".equals(attributes.get(RESOURCE_EXISTS)))) {
                throw new IllegalArgumentException("resourceExists must be true or false");
            }
        }
    }

    public static DocumentVaultAuthorizationResource forNew(UUID documentId, DocumentVaultSubject subject) {
        return new DocumentVaultAuthorizationResource(
                RESOURCE_TYPE,
                Objects.requireNonNull(documentId, "documentId must not be null").toString(),
                subject.tenantId(),
                Map.of(OWNER_ACCOUNT_ID, subject.accountId().toString()));
    }

    public static DocumentVaultAuthorizationResource fromDocument(VaultDocument document) {
        return new DocumentVaultAuthorizationResource(
                RESOURCE_TYPE,
                document.getId().toString(),
                document.getTenantId(),
                Map.of(OWNER_ACCOUNT_ID, document.getOwnerAccountId().toString(), RESOURCE_EXISTS, "true"));
    }

    public static DocumentVaultAuthorizationResource forMissing(UUID documentId, DocumentVaultSubject subject) {
        return new DocumentVaultAuthorizationResource(
                RESOURCE_TYPE,
                Objects.requireNonNull(documentId, "documentId must not be null").toString(),
                subject.tenantId(),
                Map.of(OWNER_ACCOUNT_ID, MISSING_OWNER_ACCOUNT_ID, RESOURCE_EXISTS, "false"));
    }

    public static DocumentVaultAuthorizationResource forCollection(DocumentVaultSubject subject) {
        return new DocumentVaultAuthorizationResource(RESOURCE_TYPE, null, subject.tenantId(), Map.of());
    }
}

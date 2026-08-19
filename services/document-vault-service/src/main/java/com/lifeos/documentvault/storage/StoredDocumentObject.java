package com.lifeos.documentvault.storage;

import java.util.Objects;

/** Finalized opaque storage reference. It is stored in Postgres but never returned to clients. */
public record StoredDocumentObject(String objectReference) {

    public StoredDocumentObject {
        if (objectReference == null || !objectReference.matches("[a-z][a-z0-9-]{0,31}:[A-Za-z0-9._:-]{1,120}")) {
            throw new IllegalArgumentException("objectReference must be a bounded opaque reference");
        }
    }
}

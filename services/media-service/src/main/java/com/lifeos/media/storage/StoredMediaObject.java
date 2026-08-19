package com.lifeos.media.storage;

import java.util.Objects;

/** Opaque source reference persisted in Media metadata after safe promotion. */
public record StoredMediaObject(String objectReference) {

    public StoredMediaObject {
        if (objectReference == null || !objectReference.matches("[a-z][a-z0-9-]{0,31}:[A-Za-z0-9._:-]{1,120}")) {
            throw new IllegalArgumentException("objectReference must be an opaque reference");
        }
    }
}

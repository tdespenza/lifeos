package com.lifeos.documentvault.idempotency;

/** Closed set of durable commands whose exact original response can be replayed safely. */
public enum DocumentCommandOperation {
    UPLOAD,
    METADATA_UPDATE
}

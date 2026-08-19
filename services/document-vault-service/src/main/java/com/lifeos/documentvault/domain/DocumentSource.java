package com.lifeos.documentvault.domain;

/** Source classification is metadata only; raw import payloads are never retained in Postgres. */
public enum DocumentSource {
    UPLOAD,
    SCANNER,
    IMPORT
}

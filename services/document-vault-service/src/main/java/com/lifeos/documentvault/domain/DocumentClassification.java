package com.lifeos.documentvault.domain;

/** Owner-selected handling label; later integrations must honor it before deriving content. */
public enum DocumentClassification {
    PRIVATE,
    SENSITIVE,
    CONFIDENTIAL
}

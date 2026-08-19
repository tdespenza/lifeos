package com.lifeos.documentvault.idempotency;

/** A distinct mutation already advanced the document representation. */
public class DocumentVersionConflictException extends DocumentCommandRejectedException {
}

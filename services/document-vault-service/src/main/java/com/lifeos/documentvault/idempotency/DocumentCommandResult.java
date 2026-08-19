package com.lifeos.documentvault.idempotency;

import com.lifeos.documentvault.service.DocumentView;

/** Immutable command response plus whether it came from a completed durable reservation. */
public record DocumentCommandResult(DocumentView document, boolean replayed) {
}

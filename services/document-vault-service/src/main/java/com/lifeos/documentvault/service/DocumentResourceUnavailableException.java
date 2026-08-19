package com.lifeos.documentvault.service;

import com.lifeos.documentvault.idempotency.DocumentCommandRejectedException;

/** Generic owner-scoped absence used for both a missing document and another owner's document. */
public class DocumentResourceUnavailableException extends DocumentCommandRejectedException {
}

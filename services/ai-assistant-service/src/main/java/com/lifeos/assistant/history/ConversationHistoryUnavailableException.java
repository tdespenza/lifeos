package com.lifeos.assistant.history;

/** Fail-closed signal when explicitly enabled encrypted conversation storage cannot complete. */
public class ConversationHistoryUnavailableException extends RuntimeException {

    public ConversationHistoryUnavailableException(Throwable cause) {
        super(cause);
    }
}

package com.lifeos.finance.config;

import java.io.IOException;

/** Raised when an inbound direct-service request exceeds its configured byte limit. */
public class FinancePayloadTooLargeException extends IOException {

    public FinancePayloadTooLargeException() {
        super("Finance request body exceeds the configured limit");
    }
}

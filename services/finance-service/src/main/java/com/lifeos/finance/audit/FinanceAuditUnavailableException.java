package com.lifeos.finance.audit;

/** Security audit persistence failed, so the associated decision must fail closed. */
public class FinanceAuditUnavailableException extends RuntimeException {

    public FinanceAuditUnavailableException(Throwable cause) {
        super("Finance security audit unavailable", cause);
    }
}

package com.lifeos.finance.service;

import com.lifeos.finance.idempotency.FinanceMutationRejectedException;

/** Current representation no longer matches the caller's strong ETag precondition. */
public class FinanceVersionConflictException extends FinanceMutationRejectedException {

    public FinanceVersionConflictException() {
        super("Finance representation is no longer current");
    }
}

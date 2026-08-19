package com.lifeos.finance.service;

import com.lifeos.finance.idempotency.FinanceMutationRejectedException;

/** An owner/tenant/category budget interval overlaps an existing interval. */
public class FinanceBudgetOverlapException extends FinanceMutationRejectedException {

    public FinanceBudgetOverlapException() {
        super("Finance budget period overlaps an existing budget");
    }

    public FinanceBudgetOverlapException(Throwable cause) {
        super("Finance budget period overlaps an existing budget", cause);
    }
}

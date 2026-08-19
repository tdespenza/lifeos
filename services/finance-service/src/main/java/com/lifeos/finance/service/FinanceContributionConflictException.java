package com.lifeos.finance.service;

import com.lifeos.finance.idempotency.FinanceMutationRejectedException;

/** One source transaction can be linked to a particular target only once. */
public class FinanceContributionConflictException extends FinanceMutationRejectedException {

    public FinanceContributionConflictException() {
        super("Finance contribution conflicts with the target state");
    }

    public FinanceContributionConflictException(Throwable cause) {
        super("Finance contribution conflicts with the target state", cause);
    }
}

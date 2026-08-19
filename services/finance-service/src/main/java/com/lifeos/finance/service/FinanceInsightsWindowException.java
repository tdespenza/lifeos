package com.lifeos.finance.service;

/** Insights are intentionally limited to one year plus one day to bound work and payloads. */
public class FinanceInsightsWindowException extends IllegalArgumentException {

    public FinanceInsightsWindowException() {
        super("Finance insight date range must be between one and 366 days");
    }
}

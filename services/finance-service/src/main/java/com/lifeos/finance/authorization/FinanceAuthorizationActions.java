package com.lifeos.finance.authorization;

/** Exact V2 Finance action strings registered by identity-service. */
public final class FinanceAuthorizationActions {

    public static final String BUDGET_CREATE = "finance:budget-create";
    public static final String BUDGET_LIST = "finance:budget-list";
    public static final String BUDGET_READ = "finance:budget-read";
    public static final String BUDGET_UPDATE = "finance:budget-update";
    public static final String TRANSACTION_CREATE = "finance:transaction-create";
    public static final String TRANSACTION_LIST = "finance:transaction-list";
    public static final String TRANSACTION_READ = "finance:transaction-read";
    public static final String TRANSACTION_CATEGORIZE = "finance:transaction-categorize";
    public static final String INSIGHTS_READ = "finance:insights-read";
    public static final String FORECAST_READ = "finance:forecast-read";
    public static final String GOAL_CREATE = "finance:goal-create";
    public static final String GOAL_LIST = "finance:goal-list";
    public static final String GOAL_READ = "finance:goal-read";
    public static final String GOAL_UPDATE = "finance:goal-update";
    public static final String GOAL_CONTRIBUTE = "finance:goal-contribute";

    private FinanceAuthorizationActions() {
    }
}

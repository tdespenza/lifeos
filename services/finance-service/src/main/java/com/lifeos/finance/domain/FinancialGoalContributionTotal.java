package com.lifeos.finance.domain;

import java.util.UUID;

/** Narrow aggregate projection used to avoid N+1 contribution lookups while listing goals. */
public interface FinancialGoalContributionTotal {

    UUID getGoalId();

    Long getTotalMinor();
}

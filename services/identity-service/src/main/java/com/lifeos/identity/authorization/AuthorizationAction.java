package com.lifeos.identity.authorization;

import java.util.Arrays;
import java.util.Optional;

/** Exact action values supported by the versioned authorization policy. */
public enum AuthorizationAction {

    /** Create a personal goal. */
    GOAL_CREATE("goal:create"),

    /** List the caller's personal goal collection. */
    GOAL_LIST("goal:list"),

    /** Read one persisted goal. */
    GOAL_READ("goal:read"),

    /** Rename an active persisted goal. */
    GOAL_UPDATE("goal:update"),

    /** Move an active persisted goal to completed. */
    GOAL_COMPLETE("goal:complete"),

    /** Move an active or completed persisted goal to archived. */
    GOAL_ARCHIVE("goal:archive"),

    /** Run dependency ordering within the caller's goal scope. */
    GOAL_DEPENDENCY_ORDER("goal:dependency-order"),

    /** Create a caller-owned personal profile. */
    PROFILE_CREATE("profile:create"),

    /** Read a caller-owned personal profile. */
    PROFILE_READ("profile:read"),

    /** Replace a caller-owned personal profile. */
    PROFILE_UPDATE("profile:update"),

    /** Read a caller-owned validated preference representation. */
    PROFILE_PREFERENCES_READ("profile:preferences-read"),

    /** Replace a caller-owned validated preference representation. */
    PROFILE_PREFERENCES_UPDATE("profile:preferences-update"),

    /** Read a caller-owned privacy representation. */
    PROFILE_PRIVACY_READ("profile:privacy-read"),

    /** Replace a caller-owned privacy representation. */
    PROFILE_PRIVACY_UPDATE("profile:privacy-update"),

    /** Read a caller-owned AI personalization consent representation. */
    PROFILE_AI_PERSONALIZATION_READ("profile:ai-personalization-read"),

    /** Replace a caller-owned AI personalization consent representation. */
    PROFILE_AI_PERSONALIZATION_UPDATE("profile:ai-personalization-update"),

    /** Create a household in the caller's personal tenant. */
    HOUSEHOLD_CREATE("household:create"),

    /** Request identity capability to read a locally scoped household. */
    HOUSEHOLD_READ("household:read"),

    /** Request identity capability to read locally scoped household members. */
    HOUSEHOLD_MEMBERS_READ("household:members-read"),

    /** Request identity capability to manage locally scoped household members. */
    HOUSEHOLD_MEMBERS_MANAGE("household:members-manage"),

    /** Create a caller-owned task. */
    TASK_CREATE("task:create"),

    /** List the caller's task collection. */
    TASK_LIST("task:list"),

    /** Read one caller-owned task. */
    TASK_READ("task:read"),

    /** Update one caller-owned task. */
    TASK_UPDATE("task:update"),

    /** Complete one caller-owned task. */
    TASK_COMPLETE("task:complete"),

    /** Cancel one caller-owned task. */
    TASK_CANCEL("task:cancel"),

    /** Manage edges in the caller's task-dependency graph. */
    TASK_DEPENDENCY_MANAGE("task:dependency-manage"),

    /** Request a deterministic ordering of the caller's task-dependency graph. */
    TASK_DEPENDENCY_ORDER("task:dependency-order"),

    /** Create a caller-owned habit definition. */
    HABIT_CREATE("habit:create"),

    /** List the caller's habit collection. */
    HABIT_LIST("habit:list"),

    /** Read one caller-owned habit. */
    HABIT_READ("habit:read"),

    /** Update one caller-owned habit. */
    HABIT_UPDATE("habit:update"),

    /** Record one occurrence for a caller-owned habit. */
    HABIT_OCCURRENCE_CREATE("habit:occurrence-create"),

    /** Read a caller-owned habit trend. */
    HABIT_TREND_READ("habit:trend-read"),

    /** Create a caller-owned routine definition. */
    ROUTINE_CREATE("routine:create"),

    /** List the caller's routine collection. */
    ROUTINE_LIST("routine:list"),

    /** Read one caller-owned routine. */
    ROUTINE_READ("routine:read"),

    /** Update one caller-owned routine. */
    ROUTINE_UPDATE("routine:update"),

    /** Materialize one bounded routine occurrence window. */
    ROUTINE_MATERIALIZE("routine:materialize"),

    /** Create a goal milestone. */
    MILESTONE_CREATE("milestone:create"),

    /** List milestones for a caller-owned goal. */
    MILESTONE_LIST("milestone:list"),

    /** Read one caller-owned milestone. */
    MILESTONE_READ("milestone:read"),

    /** Update milestone ordering and criteria. */
    MILESTONE_UPDATE("milestone:update"),

    /** Complete or reopen a caller-owned milestone. */
    MILESTONE_COMPLETE("milestone:complete"),

    /** Create a caller-owned calendar event. */
    CALENDAR_EVENT_CREATE("calendar:event-create"),

    /** List the caller's calendar events. */
    CALENDAR_EVENT_LIST("calendar:event-list"),

    /** Read one caller-owned calendar event. */
    CALENDAR_EVENT_READ("calendar:event-read"),

    /** Update one caller-owned calendar event. */
    CALENDAR_EVENT_UPDATE("calendar:event-update"),

    /** Cancel one caller-owned calendar event. */
    CALENDAR_EVENT_CANCEL("calendar:event-cancel"),

    /** Create a caller-owned time block. */
    CALENDAR_TIME_BLOCK_CREATE("calendar:time-block-create"),

    /** List the caller's time blocks. */
    CALENDAR_TIME_BLOCK_LIST("calendar:time-block-list"),

    /** Read one caller-owned time block. */
    CALENDAR_TIME_BLOCK_READ("calendar:time-block-read"),

    /** Update one caller-owned time block. */
    CALENDAR_TIME_BLOCK_UPDATE("calendar:time-block-update"),

    /** Cancel one caller-owned time block. */
    CALENDAR_TIME_BLOCK_CANCEL("calendar:time-block-cancel"),

    /** Read conflict information within the caller's calendar collection. */
    CALENDAR_CONFLICT_READ("calendar:conflict-read"),

    /** Request an optimization over the caller's calendar collection. */
    CALENDAR_OPTIMIZE("calendar:optimize"),

    /** Create a caller-owned budget. */
    FINANCE_BUDGET_CREATE("finance:budget-create"),

    /** List the caller's budgets. */
    FINANCE_BUDGET_LIST("finance:budget-list"),

    /** Read one caller-owned budget. */
    FINANCE_BUDGET_READ("finance:budget-read"),

    /** Update one caller-owned budget. */
    FINANCE_BUDGET_UPDATE("finance:budget-update"),

    /** Record one immutable caller-owned financial transaction. */
    FINANCE_TRANSACTION_CREATE("finance:transaction-create"),

    /** List the caller's immutable financial transactions. */
    FINANCE_TRANSACTION_LIST("finance:transaction-list"),

    /** Read one caller-owned financial transaction. */
    FINANCE_TRANSACTION_READ("finance:transaction-read"),

    /** Version the category assignment for one caller-owned financial transaction. */
    FINANCE_TRANSACTION_CATEGORIZE("finance:transaction-categorize"),

    /** Read caller-owned finance aggregates and spending insights. */
    FINANCE_INSIGHTS_READ("finance:insights-read"),

    /** Read a non-mutating caller-owned finance forecast. */
    FINANCE_FORECAST_READ("finance:forecast-read"),

    /** Create a caller-owned financial goal. */
    FINANCE_GOAL_CREATE("finance:goal-create"),

    /** List the caller's financial goals. */
    FINANCE_GOAL_LIST("finance:goal-list"),

    /** Read one caller-owned financial goal. */
    FINANCE_GOAL_READ("finance:goal-read"),

    /** Update one caller-owned financial goal. */
    FINANCE_GOAL_UPDATE("finance:goal-update"),

    /** Record a contribution against one caller-owned financial goal. */
    FINANCE_GOAL_CONTRIBUTE("finance:goal-contribute"),

    /** Create a caller-owned document proof request. */
    TRUST_DOCUMENT_PROOF_CREATE("trust:document-proof-create"),

    /** Create a caller-owned Merkle proof request. */
    TRUST_MERKLE_PROOF_CREATE("trust:merkle-proof-create"),

    /** Verify a caller-owned proof. */
    TRUST_PROOF_VERIFY("trust:proof-verify"),

    /** Create a caller-owned anchoring request. */
    TRUST_ANCHOR_CREATE("trust:anchor-create"),

    /** Verify a caller-owned credential. */
    TRUST_CREDENTIAL_VERIFY("trust:credential-verify"),

    /** Create a caller-owned AI audit anchoring request. */
    TRUST_AI_AUDIT_ANCHOR_CREATE("trust:ai-audit-anchor-create"),

    /** Create a caller-owned goal-certificate request. */
    TRUST_GOAL_CERTIFICATE_CREATE("trust:goal-certificate-create"),

    /** Create a caller-owned session-summary anchoring request. */
    TRUST_SESSION_SUMMARY_ANCHOR_CREATE("trust:session-summary-anchor"),

    /** Create a caller-owned document. */
    DOCUMENT_CREATE("document:create"),

    /** Read one caller-owned document. */
    DOCUMENT_READ("document:read"),

    /** Update one caller-owned document. */
    DOCUMENT_UPDATE("document:update"),

    /** Search the caller's document collection. */
    DOCUMENT_SEARCH("document:search"),

    /** Request a durable proof workflow for one caller-owned document. */
    DOCUMENT_PROOF_REQUEST("document:proof-request"),

    /** Create a caller-owned media asset. */
    MEDIA_ASSET_CREATE("media:asset-create"),

    /** List the caller's media assets. */
    MEDIA_ASSET_LIST("media:asset-list"),

    /** Read one caller-owned media asset. */
    MEDIA_ASSET_READ("media:asset-read"),

    /** Upload content to one caller-owned media asset. */
    MEDIA_ASSET_UPLOAD("media:asset-upload"),

    /** Read an HLS manifest for one caller-owned media asset. */
    MEDIA_HLS_MANIFEST_READ("media:hls-manifest-read"),

    /** Read an HLS segment for one caller-owned media asset. */
    MEDIA_HLS_SEGMENT_READ("media:hls-segment-read"),

    /** Create a caller-owned media session. */
    MEDIA_SESSION_CREATE("media:session-create"),

    /** List the caller's media sessions. */
    MEDIA_SESSION_LIST("media:session-list"),

    /** Read one caller-owned media session. */
    MEDIA_SESSION_READ("media:session-read"),

    /** Update one caller-owned media session. */
    MEDIA_SESSION_UPDATE("media:session-update"),

    /** Cancel one caller-owned media session. */
    MEDIA_SESSION_CANCEL("media:session-cancel"),

    /** Join one caller-owned media session. */
    MEDIA_SESSION_JOIN("media:session-join"),

    /** Read the caller's bounded analytics dashboard projection. */
    ANALYTICS_DASHBOARD_READ("analytics:dashboard-read");

    private final String value;

    AuthorizationAction(String value) {
        this.value = value;
    }

    /**
     * Returns the stable wire value used by protected services.
     *
     * @return exact action identifier
     */
    public String value() {
        return value;
    }

    /**
     * Resolves an exact policy action without case folding or aliasing.
     *
     * @param value untrusted action value
     * @return matching action when supported
     */
    public static Optional<AuthorizationAction> fromValue(String value) {
        return Arrays.stream(values()).filter(action -> action.value.equals(value)).findFirst();
    }
}

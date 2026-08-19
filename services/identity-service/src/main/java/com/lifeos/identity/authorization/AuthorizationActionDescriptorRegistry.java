package com.lifeos.identity.authorization;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Source-defined authorization action registry for the bounded V2 decision model.
 *
 * <p>Every {@link AuthorizationAction} has one exact descriptor. There is deliberately no
 * descriptor wildcard by resource type, action family, or workload identity. The registry is kept
 * in Identity so a protected service cannot claim a broader family merely by changing its local
 * adapter configuration.
 */
@Component
public class AuthorizationActionDescriptorRegistry {

    /** Workload that owns the task and goal resource families. */
    public static final String TASK_GOAL_WORKLOAD = "task-goal-service";

    /** Workload that owns profile and household resource families. */
    public static final String PROFILE_WORKLOAD = "profile-service";

    /** Workload that owns calendar resource families. */
    public static final String CALENDAR_WORKLOAD = "calendar-service";

    /** Workload that owns finance resource families. */
    public static final String FINANCE_WORKLOAD = "finance-service";

    /** Workload that owns trust-ledger resource families. */
    public static final String TRUST_LEDGER_WORKLOAD = "trust-ledger-service";

    /** Workload that owns document-vault resource families. */
    public static final String DOCUMENT_VAULT_WORKLOAD = "document-vault-service";

    /** Workload that owns media resource families. */
    public static final String MEDIA_WORKLOAD = "media-service";

    /** Workload that owns the privacy-minimized analytics projection. */
    public static final String ANALYTICS_WORKLOAD = "analytics-service";

    private final Map<AuthorizationAction, AuthorizationActionDescriptor> descriptors;

    /** Creates and validates the complete immutable descriptor table. */
    public AuthorizationActionDescriptorRegistry() {
        Map<AuthorizationAction, AuthorizationActionDescriptor> configured =
                new EnumMap<>(AuthorizationAction.class);

        registerGoalDescriptors(configured);
        registerProfileDescriptors(configured);
        registerHouseholdDescriptors(configured);
        registerTaskDescriptors(configured);
        registerCalendarDescriptors(configured);
        registerFinanceDescriptors(configured);
        registerTrustLedgerDescriptors(configured);
        registerDocumentVaultDescriptors(configured);
        registerMediaDescriptors(configured);
        registerAnalyticsDescriptors(configured);

        EnumSet<AuthorizationAction> missing = EnumSet.allOf(AuthorizationAction.class);
        missing.removeAll(configured.keySet());
        if (!missing.isEmpty()) {
            throw new IllegalStateException("authorization actions require exact descriptors");
        }
        this.descriptors = Collections.unmodifiableMap(new EnumMap<>(configured));
    }

    /**
     * Looks up the one exact descriptor for an action.
     *
     * @param action closed action enum value
     * @return descriptor when the static registry contains the action
     */
    public Optional<AuthorizationActionDescriptor> find(AuthorizationAction action) {
        return Optional.ofNullable(descriptors.get(action));
    }

    /**
     * Returns the immutable descriptor table for invariant and policy tests.
     *
     * @return exact action descriptor mapping
     */
    public Map<AuthorizationAction, AuthorizationActionDescriptor> descriptors() {
        return descriptors;
    }

    private void registerGoalDescriptors(Map<AuthorizationAction, AuthorizationActionDescriptor> configured) {
        register(configured, descriptor(
                AuthorizationAction.GOAL_CREATE,
                TASK_GOAL_WORKLOAD,
                "goal",
                AuthorizationResourceShape.OWNED_CREATE,
                AuthorizationTenantScope.PERSONAL,
                AuthorizationOwnerRule.SUBJECT_ONLY));
        register(configured, descriptor(
                AuthorizationAction.GOAL_LIST,
                TASK_GOAL_WORKLOAD,
                "goal",
                AuthorizationResourceShape.TENANT_COLLECTION,
                AuthorizationTenantScope.SCOPED,
                AuthorizationOwnerRule.NONE));
        registerGoalObjectDescriptor(configured, AuthorizationAction.GOAL_READ);
        registerGoalObjectDescriptor(configured, AuthorizationAction.GOAL_UPDATE);
        registerGoalObjectDescriptor(configured, AuthorizationAction.GOAL_COMPLETE);
        registerGoalObjectDescriptor(configured, AuthorizationAction.GOAL_ARCHIVE);
        register(configured, descriptor(
                AuthorizationAction.GOAL_DEPENDENCY_ORDER,
                TASK_GOAL_WORKLOAD,
                "goal",
                AuthorizationResourceShape.TENANT_COLLECTION,
                AuthorizationTenantScope.SCOPED,
                AuthorizationOwnerRule.NONE));
    }

    private void registerGoalObjectDescriptor(
            Map<AuthorizationAction, AuthorizationActionDescriptor> configured, AuthorizationAction action) {
        register(configured, descriptor(
                action,
                TASK_GOAL_WORKLOAD,
                "goal",
                AuthorizationResourceShape.OWNED_OBJECT,
                AuthorizationTenantScope.SCOPED,
                AuthorizationOwnerRule.SUBJECT_OR_TENANT_ADMIN));
    }

    private void registerProfileDescriptors(Map<AuthorizationAction, AuthorizationActionDescriptor> configured) {
        register(configured, descriptor(
                AuthorizationAction.PROFILE_CREATE,
                PROFILE_WORKLOAD,
                "profile",
                AuthorizationResourceShape.OWNED_CREATE,
                AuthorizationTenantScope.PERSONAL,
                AuthorizationOwnerRule.SUBJECT_ONLY));
        registerProfileObjectDescriptor(configured, AuthorizationAction.PROFILE_READ);
        registerProfileObjectDescriptor(configured, AuthorizationAction.PROFILE_UPDATE);
        registerProfileObjectDescriptor(configured, AuthorizationAction.PROFILE_PREFERENCES_READ);
        registerProfileObjectDescriptor(configured, AuthorizationAction.PROFILE_PREFERENCES_UPDATE);
        registerProfileObjectDescriptor(configured, AuthorizationAction.PROFILE_PRIVACY_READ);
        registerProfileObjectDescriptor(configured, AuthorizationAction.PROFILE_PRIVACY_UPDATE);
        registerProfileObjectDescriptor(configured, AuthorizationAction.PROFILE_AI_PERSONALIZATION_READ);
        registerProfileObjectDescriptor(configured, AuthorizationAction.PROFILE_AI_PERSONALIZATION_UPDATE);
    }

    private void registerProfileObjectDescriptor(
            Map<AuthorizationAction, AuthorizationActionDescriptor> configured, AuthorizationAction action) {
        register(configured, descriptor(
                action,
                PROFILE_WORKLOAD,
                "profile",
                AuthorizationResourceShape.OWNED_OBJECT,
                AuthorizationTenantScope.PERSONAL,
                AuthorizationOwnerRule.SUBJECT_ONLY));
    }

    private void registerHouseholdDescriptors(Map<AuthorizationAction, AuthorizationActionDescriptor> configured) {
        registerHouseholdDescriptor(configured, AuthorizationAction.HOUSEHOLD_CREATE);
        registerHouseholdDescriptor(configured, AuthorizationAction.HOUSEHOLD_READ);
        registerHouseholdDescriptor(configured, AuthorizationAction.HOUSEHOLD_MEMBERS_READ);
        registerHouseholdDescriptor(configured, AuthorizationAction.HOUSEHOLD_MEMBERS_MANAGE);
    }

    private void registerHouseholdDescriptor(
            Map<AuthorizationAction, AuthorizationActionDescriptor> configured, AuthorizationAction action) {
        register(configured, descriptor(
                action,
                PROFILE_WORKLOAD,
                "household",
                AuthorizationResourceShape.REQUESTER_CAPABILITY,
                AuthorizationTenantScope.PERSONAL,
                AuthorizationOwnerRule.REQUESTER_SUBJECT));
    }

    private void registerTaskDescriptors(Map<AuthorizationAction, AuthorizationActionDescriptor> configured) {
        register(configured, descriptor(
                AuthorizationAction.TASK_CREATE,
                TASK_GOAL_WORKLOAD,
                "task",
                AuthorizationResourceShape.OWNED_CREATE,
                AuthorizationTenantScope.PERSONAL,
                AuthorizationOwnerRule.SUBJECT_ONLY));
        register(configured, descriptor(
                AuthorizationAction.TASK_LIST,
                TASK_GOAL_WORKLOAD,
                "task",
                AuthorizationResourceShape.TENANT_COLLECTION,
                AuthorizationTenantScope.PERSONAL,
                AuthorizationOwnerRule.NONE));
        registerTaskObjectDescriptor(configured, AuthorizationAction.TASK_READ);
        registerTaskObjectDescriptor(configured, AuthorizationAction.TASK_UPDATE);
        registerTaskObjectDescriptor(configured, AuthorizationAction.TASK_COMPLETE);
        registerTaskObjectDescriptor(configured, AuthorizationAction.TASK_CANCEL);
        registerTaskDependencyGraphDescriptor(configured, AuthorizationAction.TASK_DEPENDENCY_MANAGE);
        registerTaskDependencyGraphDescriptor(configured, AuthorizationAction.TASK_DEPENDENCY_ORDER);
        registerPlanningDescriptors(configured);
    }

    private void registerPlanningDescriptors(Map<AuthorizationAction, AuthorizationActionDescriptor> configured) {
        registerPlanningObjectDescriptor(configured, AuthorizationAction.HABIT_CREATE, "habit",
                AuthorizationResourceShape.OWNED_CREATE);
        registerPlanningCollectionDescriptor(configured, AuthorizationAction.HABIT_LIST, "habit");
        registerPlanningObjectDescriptor(configured, AuthorizationAction.HABIT_READ, "habit",
                AuthorizationResourceShape.OWNED_OBJECT);
        registerPlanningObjectDescriptor(configured, AuthorizationAction.HABIT_UPDATE, "habit",
                AuthorizationResourceShape.OWNED_OBJECT);
        registerPlanningObjectDescriptor(configured, AuthorizationAction.HABIT_OCCURRENCE_CREATE, "habit",
                AuthorizationResourceShape.OWNED_OBJECT);
        registerPlanningObjectDescriptor(configured, AuthorizationAction.HABIT_TREND_READ, "habit",
                AuthorizationResourceShape.OWNED_OBJECT);

        registerPlanningObjectDescriptor(configured, AuthorizationAction.ROUTINE_CREATE, "routine",
                AuthorizationResourceShape.OWNED_CREATE);
        registerPlanningCollectionDescriptor(configured, AuthorizationAction.ROUTINE_LIST, "routine");
        registerPlanningObjectDescriptor(configured, AuthorizationAction.ROUTINE_READ, "routine",
                AuthorizationResourceShape.OWNED_OBJECT);
        registerPlanningObjectDescriptor(configured, AuthorizationAction.ROUTINE_UPDATE, "routine",
                AuthorizationResourceShape.OWNED_OBJECT);
        registerPlanningObjectDescriptor(configured, AuthorizationAction.ROUTINE_MATERIALIZE, "routine",
                AuthorizationResourceShape.OWNED_OBJECT);

        registerPlanningObjectDescriptor(configured, AuthorizationAction.MILESTONE_CREATE, "milestone",
                AuthorizationResourceShape.OWNED_CREATE);
        registerPlanningCollectionDescriptor(configured, AuthorizationAction.MILESTONE_LIST, "milestone");
        registerPlanningObjectDescriptor(configured, AuthorizationAction.MILESTONE_READ, "milestone",
                AuthorizationResourceShape.OWNED_OBJECT);
        registerPlanningObjectDescriptor(configured, AuthorizationAction.MILESTONE_UPDATE, "milestone",
                AuthorizationResourceShape.OWNED_OBJECT);
        registerPlanningObjectDescriptor(configured, AuthorizationAction.MILESTONE_COMPLETE, "milestone",
                AuthorizationResourceShape.OWNED_OBJECT);
    }

    private void registerPlanningObjectDescriptor(
            Map<AuthorizationAction, AuthorizationActionDescriptor> configured,
            AuthorizationAction action,
            String resourceType,
            AuthorizationResourceShape shape) {
        register(configured, descriptor(
                action,
                TASK_GOAL_WORKLOAD,
                resourceType,
                shape,
                AuthorizationTenantScope.PERSONAL,
                ownerRuleFor(shape)));
    }

    private void registerPlanningCollectionDescriptor(
            Map<AuthorizationAction, AuthorizationActionDescriptor> configured,
            AuthorizationAction action,
            String resourceType) {
        register(configured, descriptor(
                action,
                TASK_GOAL_WORKLOAD,
                resourceType,
                AuthorizationResourceShape.TENANT_COLLECTION,
                AuthorizationTenantScope.PERSONAL,
                AuthorizationOwnerRule.NONE));
    }

    private void registerTaskObjectDescriptor(
            Map<AuthorizationAction, AuthorizationActionDescriptor> configured, AuthorizationAction action) {
        register(configured, descriptor(
                action,
                TASK_GOAL_WORKLOAD,
                "task",
                AuthorizationResourceShape.OWNED_OBJECT,
                AuthorizationTenantScope.PERSONAL,
                AuthorizationOwnerRule.SUBJECT_ONLY));
    }

    private void registerTaskDependencyGraphDescriptor(
            Map<AuthorizationAction, AuthorizationActionDescriptor> configured, AuthorizationAction action) {
        register(configured, descriptor(
                action,
                TASK_GOAL_WORKLOAD,
                "task-dependency-graph",
                AuthorizationResourceShape.TENANT_COLLECTION,
                AuthorizationTenantScope.PERSONAL,
                AuthorizationOwnerRule.NONE));
    }

    private void registerCalendarDescriptors(Map<AuthorizationAction, AuthorizationActionDescriptor> configured) {
        register(configured, descriptor(
                AuthorizationAction.CALENDAR_EVENT_CREATE,
                CALENDAR_WORKLOAD,
                "calendar-event",
                AuthorizationResourceShape.OWNED_CREATE,
                AuthorizationTenantScope.PERSONAL,
                AuthorizationOwnerRule.SUBJECT_ONLY));
        register(configured, descriptor(
                AuthorizationAction.CALENDAR_EVENT_LIST,
                CALENDAR_WORKLOAD,
                "calendar",
                AuthorizationResourceShape.TENANT_COLLECTION,
                AuthorizationTenantScope.PERSONAL,
                AuthorizationOwnerRule.NONE));
        registerCalendarEventObjectDescriptor(configured, AuthorizationAction.CALENDAR_EVENT_READ);
        registerCalendarEventObjectDescriptor(configured, AuthorizationAction.CALENDAR_EVENT_UPDATE);
        registerCalendarEventObjectDescriptor(configured, AuthorizationAction.CALENDAR_EVENT_CANCEL);

        register(configured, descriptor(
                AuthorizationAction.CALENDAR_TIME_BLOCK_CREATE,
                CALENDAR_WORKLOAD,
                "calendar-time-block",
                AuthorizationResourceShape.OWNED_CREATE,
                AuthorizationTenantScope.PERSONAL,
                AuthorizationOwnerRule.SUBJECT_ONLY));
        register(configured, descriptor(
                AuthorizationAction.CALENDAR_TIME_BLOCK_LIST,
                CALENDAR_WORKLOAD,
                "calendar",
                AuthorizationResourceShape.TENANT_COLLECTION,
                AuthorizationTenantScope.PERSONAL,
                AuthorizationOwnerRule.NONE));
        registerCalendarTimeBlockObjectDescriptor(configured, AuthorizationAction.CALENDAR_TIME_BLOCK_READ);
        registerCalendarTimeBlockObjectDescriptor(configured, AuthorizationAction.CALENDAR_TIME_BLOCK_UPDATE);
        registerCalendarTimeBlockObjectDescriptor(configured, AuthorizationAction.CALENDAR_TIME_BLOCK_CANCEL);
        registerCalendarCollectionDescriptor(configured, AuthorizationAction.CALENDAR_CONFLICT_READ);
        registerCalendarCollectionDescriptor(configured, AuthorizationAction.CALENDAR_OPTIMIZE);
    }

    private void registerCalendarEventObjectDescriptor(
            Map<AuthorizationAction, AuthorizationActionDescriptor> configured, AuthorizationAction action) {
        register(configured, descriptor(
                action,
                CALENDAR_WORKLOAD,
                "calendar-event",
                AuthorizationResourceShape.OWNED_OBJECT,
                AuthorizationTenantScope.PERSONAL,
                AuthorizationOwnerRule.SUBJECT_ONLY));
    }

    private void registerCalendarTimeBlockObjectDescriptor(
            Map<AuthorizationAction, AuthorizationActionDescriptor> configured, AuthorizationAction action) {
        register(configured, descriptor(
                action,
                CALENDAR_WORKLOAD,
                "calendar-time-block",
                AuthorizationResourceShape.OWNED_OBJECT,
                AuthorizationTenantScope.PERSONAL,
                AuthorizationOwnerRule.SUBJECT_ONLY));
    }

    private void registerCalendarCollectionDescriptor(
            Map<AuthorizationAction, AuthorizationActionDescriptor> configured, AuthorizationAction action) {
        register(configured, descriptor(
                action,
                CALENDAR_WORKLOAD,
                "calendar",
                AuthorizationResourceShape.TENANT_COLLECTION,
                AuthorizationTenantScope.PERSONAL,
                AuthorizationOwnerRule.NONE));
    }

    private void registerFinanceDescriptors(Map<AuthorizationAction, AuthorizationActionDescriptor> configured) {
        registerFinanceBudgetDescriptor(configured, AuthorizationAction.FINANCE_BUDGET_CREATE,
                AuthorizationResourceShape.OWNED_CREATE);
        registerFinanceBudgetDescriptor(configured, AuthorizationAction.FINANCE_BUDGET_LIST,
                AuthorizationResourceShape.TENANT_COLLECTION);
        registerFinanceBudgetDescriptor(configured, AuthorizationAction.FINANCE_BUDGET_READ,
                AuthorizationResourceShape.OWNED_OBJECT);
        registerFinanceBudgetDescriptor(configured, AuthorizationAction.FINANCE_BUDGET_UPDATE,
                AuthorizationResourceShape.OWNED_OBJECT);

        registerFinanceTransactionDescriptor(configured, AuthorizationAction.FINANCE_TRANSACTION_CREATE,
                AuthorizationResourceShape.OWNED_CREATE);
        registerFinanceTransactionDescriptor(configured, AuthorizationAction.FINANCE_TRANSACTION_LIST,
                AuthorizationResourceShape.TENANT_COLLECTION);
        registerFinanceTransactionDescriptor(configured, AuthorizationAction.FINANCE_TRANSACTION_READ,
                AuthorizationResourceShape.OWNED_OBJECT);
        registerFinanceTransactionDescriptor(configured, AuthorizationAction.FINANCE_TRANSACTION_CATEGORIZE,
                AuthorizationResourceShape.OWNED_OBJECT);

        registerFinanceCollectionDescriptor(configured, AuthorizationAction.FINANCE_INSIGHTS_READ);
        registerFinanceCollectionDescriptor(configured, AuthorizationAction.FINANCE_FORECAST_READ);

        registerFinanceGoalDescriptor(configured, AuthorizationAction.FINANCE_GOAL_CREATE,
                AuthorizationResourceShape.OWNED_CREATE);
        registerFinanceGoalDescriptor(configured, AuthorizationAction.FINANCE_GOAL_LIST,
                AuthorizationResourceShape.TENANT_COLLECTION);
        registerFinanceGoalDescriptor(configured, AuthorizationAction.FINANCE_GOAL_READ,
                AuthorizationResourceShape.OWNED_OBJECT);
        registerFinanceGoalDescriptor(configured, AuthorizationAction.FINANCE_GOAL_UPDATE,
                AuthorizationResourceShape.OWNED_OBJECT);
        registerFinanceGoalDescriptor(configured, AuthorizationAction.FINANCE_GOAL_CONTRIBUTE,
                AuthorizationResourceShape.OWNED_OBJECT);
    }

    private void registerFinanceBudgetDescriptor(
            Map<AuthorizationAction, AuthorizationActionDescriptor> configured,
            AuthorizationAction action,
            AuthorizationResourceShape shape) {
        register(configured, descriptor(
                action,
                FINANCE_WORKLOAD,
                "finance-budget",
                shape,
                AuthorizationTenantScope.PERSONAL,
                ownerRuleFor(shape)));
    }

    private void registerFinanceTransactionDescriptor(
            Map<AuthorizationAction, AuthorizationActionDescriptor> configured,
            AuthorizationAction action,
            AuthorizationResourceShape shape) {
        register(configured, descriptor(
                action,
                FINANCE_WORKLOAD,
                "finance-transaction",
                shape,
                AuthorizationTenantScope.PERSONAL,
                ownerRuleFor(shape)));
    }

    private void registerFinanceGoalDescriptor(
            Map<AuthorizationAction, AuthorizationActionDescriptor> configured,
            AuthorizationAction action,
            AuthorizationResourceShape shape) {
        register(configured, descriptor(
                action,
                FINANCE_WORKLOAD,
                "finance-goal",
                shape,
                AuthorizationTenantScope.PERSONAL,
                ownerRuleFor(shape)));
    }

    private void registerFinanceCollectionDescriptor(
            Map<AuthorizationAction, AuthorizationActionDescriptor> configured, AuthorizationAction action) {
        register(configured, descriptor(
                action,
                FINANCE_WORKLOAD,
                "finance",
                AuthorizationResourceShape.TENANT_COLLECTION,
                AuthorizationTenantScope.PERSONAL,
                AuthorizationOwnerRule.NONE));
    }

    private void registerTrustLedgerDescriptors(
            Map<AuthorizationAction, AuthorizationActionDescriptor> configured) {
        registerTrustLedgerDescriptor(configured, AuthorizationAction.TRUST_DOCUMENT_PROOF_CREATE);
        registerTrustLedgerDescriptor(configured, AuthorizationAction.TRUST_MERKLE_PROOF_CREATE);
        registerTrustLedgerDescriptor(configured, AuthorizationAction.TRUST_PROOF_VERIFY);
        registerTrustLedgerDescriptor(configured, AuthorizationAction.TRUST_ANCHOR_CREATE);
        registerTrustLedgerDescriptor(configured, AuthorizationAction.TRUST_CREDENTIAL_VERIFY);
        registerTrustLedgerDescriptor(configured, AuthorizationAction.TRUST_AI_AUDIT_ANCHOR_CREATE);
        registerTrustLedgerDescriptor(configured, AuthorizationAction.TRUST_GOAL_CERTIFICATE_CREATE);
        registerTrustLedgerDescriptor(configured, AuthorizationAction.TRUST_SESSION_SUMMARY_ANCHOR_CREATE);
    }

    private void registerTrustLedgerDescriptor(
            Map<AuthorizationAction, AuthorizationActionDescriptor> configured, AuthorizationAction action) {
        register(configured, descriptor(
                action,
                TRUST_LEDGER_WORKLOAD,
                "trust-ledger",
                AuthorizationResourceShape.TENANT_COLLECTION,
                AuthorizationTenantScope.PERSONAL,
                AuthorizationOwnerRule.NONE));
    }

    private void registerDocumentVaultDescriptors(
            Map<AuthorizationAction, AuthorizationActionDescriptor> configured) {
        register(configured, descriptor(
                AuthorizationAction.DOCUMENT_CREATE,
                DOCUMENT_VAULT_WORKLOAD,
                "document",
                AuthorizationResourceShape.OWNED_CREATE,
                AuthorizationTenantScope.PERSONAL,
                AuthorizationOwnerRule.SUBJECT_ONLY));
        registerDocumentObjectDescriptor(configured, AuthorizationAction.DOCUMENT_READ);
        registerDocumentObjectDescriptor(configured, AuthorizationAction.DOCUMENT_UPDATE);
        registerDocumentObjectDescriptor(configured, AuthorizationAction.DOCUMENT_PROOF_REQUEST);
        register(configured, descriptor(
                AuthorizationAction.DOCUMENT_SEARCH,
                DOCUMENT_VAULT_WORKLOAD,
                "document",
                AuthorizationResourceShape.TENANT_COLLECTION,
                AuthorizationTenantScope.PERSONAL,
                AuthorizationOwnerRule.NONE));
    }

    private void registerDocumentObjectDescriptor(
            Map<AuthorizationAction, AuthorizationActionDescriptor> configured, AuthorizationAction action) {
        register(configured, descriptor(
                action,
                DOCUMENT_VAULT_WORKLOAD,
                "document",
                AuthorizationResourceShape.OWNED_OBJECT,
                AuthorizationTenantScope.PERSONAL,
                AuthorizationOwnerRule.SUBJECT_ONLY));
    }

    private void registerMediaDescriptors(Map<AuthorizationAction, AuthorizationActionDescriptor> configured) {
        registerMediaAssetDescriptor(
                configured, AuthorizationAction.MEDIA_ASSET_CREATE, AuthorizationResourceShape.OWNED_CREATE);
        registerMediaAssetDescriptor(
                configured, AuthorizationAction.MEDIA_ASSET_LIST, AuthorizationResourceShape.TENANT_COLLECTION);
        registerMediaAssetDescriptor(
                configured, AuthorizationAction.MEDIA_ASSET_READ, AuthorizationResourceShape.OWNED_OBJECT);
        registerMediaAssetDescriptor(
                configured, AuthorizationAction.MEDIA_ASSET_UPLOAD, AuthorizationResourceShape.OWNED_OBJECT);
        registerMediaAssetDescriptor(
                configured,
                AuthorizationAction.MEDIA_HLS_MANIFEST_READ,
                AuthorizationResourceShape.OWNED_OBJECT);
        registerMediaAssetDescriptor(
                configured,
                AuthorizationAction.MEDIA_HLS_SEGMENT_READ,
                AuthorizationResourceShape.OWNED_OBJECT);

        registerMediaSessionDescriptor(
                configured, AuthorizationAction.MEDIA_SESSION_CREATE, AuthorizationResourceShape.OWNED_CREATE);
        registerMediaSessionDescriptor(
                configured, AuthorizationAction.MEDIA_SESSION_LIST, AuthorizationResourceShape.TENANT_COLLECTION);
        registerMediaSessionDescriptor(
                configured, AuthorizationAction.MEDIA_SESSION_READ, AuthorizationResourceShape.OWNED_OBJECT);
        registerMediaSessionDescriptor(
                configured, AuthorizationAction.MEDIA_SESSION_UPDATE, AuthorizationResourceShape.OWNED_OBJECT);
        registerMediaSessionDescriptor(
                configured, AuthorizationAction.MEDIA_SESSION_CANCEL, AuthorizationResourceShape.OWNED_OBJECT);
        registerMediaSessionDescriptor(
                configured, AuthorizationAction.MEDIA_SESSION_JOIN, AuthorizationResourceShape.OWNED_OBJECT);
    }

    private void registerAnalyticsDescriptors(Map<AuthorizationAction, AuthorizationActionDescriptor> configured) {
        register(configured, descriptor(
                AuthorizationAction.ANALYTICS_DASHBOARD_READ,
                ANALYTICS_WORKLOAD,
                "analytics",
                AuthorizationResourceShape.TENANT_COLLECTION,
                AuthorizationTenantScope.PERSONAL,
                AuthorizationOwnerRule.NONE));
    }

    private void registerMediaAssetDescriptor(
            Map<AuthorizationAction, AuthorizationActionDescriptor> configured,
            AuthorizationAction action,
            AuthorizationResourceShape shape) {
        register(configured, descriptor(
                action,
                MEDIA_WORKLOAD,
                shape == AuthorizationResourceShape.TENANT_COLLECTION ? "media" : "media-asset",
                shape,
                AuthorizationTenantScope.PERSONAL,
                ownerRuleFor(shape)));
    }

    private void registerMediaSessionDescriptor(
            Map<AuthorizationAction, AuthorizationActionDescriptor> configured,
            AuthorizationAction action,
            AuthorizationResourceShape shape) {
        register(configured, descriptor(
                action,
                MEDIA_WORKLOAD,
                shape == AuthorizationResourceShape.TENANT_COLLECTION ? "media" : "media-session",
                shape,
                AuthorizationTenantScope.PERSONAL,
                ownerRuleFor(shape)));
    }

    private AuthorizationOwnerRule ownerRuleFor(AuthorizationResourceShape shape) {
        return switch (shape) {
            case OWNED_CREATE, OWNED_OBJECT -> AuthorizationOwnerRule.SUBJECT_ONLY;
            case TENANT_COLLECTION -> AuthorizationOwnerRule.NONE;
            case REQUESTER_CAPABILITY -> throw new IllegalArgumentException(
                    "resource-family descriptors do not use requester capabilities");
        };
    }

    private AuthorizationActionDescriptor descriptor(
            AuthorizationAction action,
            String workload,
            String resourceType,
            AuthorizationResourceShape shape,
            AuthorizationTenantScope tenantScope,
            AuthorizationOwnerRule ownerRule) {
        return new AuthorizationActionDescriptor(action, workload, resourceType, shape, tenantScope, ownerRule);
    }

    private void register(
            Map<AuthorizationAction, AuthorizationActionDescriptor> configured,
            AuthorizationActionDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor must not be null");
        if (configured.putIfAbsent(descriptor.action(), descriptor) != null) {
            throw new IllegalStateException("authorization action has more than one descriptor");
        }
    }
}

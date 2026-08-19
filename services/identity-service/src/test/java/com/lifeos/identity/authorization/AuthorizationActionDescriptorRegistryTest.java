package com.lifeos.identity.authorization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** Exhaustive invariants for the closed action/workload/resource descriptor table. */
class AuthorizationActionDescriptorRegistryTest {

    private final AuthorizationActionDescriptorRegistry registry = new AuthorizationActionDescriptorRegistry();

    @Test
    void hasOneExactNonWildcardDescriptorForEveryClosedAction() {
        Map<AuthorizationAction, AuthorizationActionDescriptor> descriptors = registry.descriptors();

        assertThat(descriptors.keySet()).containsExactlyInAnyOrder(AuthorizationAction.values());
        assertThat(descriptors.values()).allSatisfy(descriptor -> {
            assertThat(descriptor.action().value()).doesNotContain("*");
            assertThat(descriptor.workloadIdentity()).doesNotContain("*");
            assertThat(descriptor.resourceType()).doesNotContain("*");
            assertThat(descriptor.workloadIdentity()).matches("[a-z][a-z0-9-]{0,63}");
            assertThat(descriptor.resourceType()).matches("[a-z][a-z0-9-]{0,63}");
        });
    }

    @Test
    void mapsLegacyActionsToTheirExistingWorkloadAndAbacContracts() {
        assertDescriptor(
                AuthorizationAction.GOAL_CREATE,
                AuthorizationActionDescriptorRegistry.TASK_GOAL_WORKLOAD,
                "goal",
                AuthorizationResourceShape.OWNED_CREATE,
                AuthorizationTenantScope.PERSONAL,
                AuthorizationOwnerRule.SUBJECT_ONLY);
        assertDescriptor(
                AuthorizationAction.GOAL_READ,
                AuthorizationActionDescriptorRegistry.TASK_GOAL_WORKLOAD,
                "goal",
                AuthorizationResourceShape.OWNED_OBJECT,
                AuthorizationTenantScope.SCOPED,
                AuthorizationOwnerRule.SUBJECT_OR_TENANT_ADMIN);
        assertDescriptor(
                AuthorizationAction.GOAL_LIST,
                AuthorizationActionDescriptorRegistry.TASK_GOAL_WORKLOAD,
                "goal",
                AuthorizationResourceShape.TENANT_COLLECTION,
                AuthorizationTenantScope.SCOPED,
                AuthorizationOwnerRule.NONE);
        assertDescriptor(
                AuthorizationAction.PROFILE_PRIVACY_READ,
                AuthorizationActionDescriptorRegistry.PROFILE_WORKLOAD,
                "profile",
                AuthorizationResourceShape.OWNED_OBJECT,
                AuthorizationTenantScope.PERSONAL,
                AuthorizationOwnerRule.SUBJECT_ONLY);
        assertDescriptor(
                AuthorizationAction.HOUSEHOLD_MEMBERS_MANAGE,
                AuthorizationActionDescriptorRegistry.PROFILE_WORKLOAD,
                "household",
                AuthorizationResourceShape.REQUESTER_CAPABILITY,
                AuthorizationTenantScope.PERSONAL,
                AuthorizationOwnerRule.REQUESTER_SUBJECT);
    }

    @Test
    void mapsTheTaskDependencyGraphToTheExactSelfOnlyCollectionContract() {
        assertDescriptor(
                AuthorizationAction.TASK_DEPENDENCY_MANAGE,
                AuthorizationActionDescriptorRegistry.TASK_GOAL_WORKLOAD,
                "task-dependency-graph",
                AuthorizationResourceShape.TENANT_COLLECTION,
                AuthorizationTenantScope.PERSONAL,
                AuthorizationOwnerRule.NONE);
        assertDescriptor(
                AuthorizationAction.TASK_DEPENDENCY_ORDER,
                AuthorizationActionDescriptorRegistry.TASK_GOAL_WORKLOAD,
                "task-dependency-graph",
                AuthorizationResourceShape.TENANT_COLLECTION,
                AuthorizationTenantScope.PERSONAL,
                AuthorizationOwnerRule.NONE);
    }

    @Test
    void mapsEveryCalendarActionToCalendarWorkloadWithNoAdminBypass() {
        for (AuthorizationAction action : new AuthorizationAction[] {
                AuthorizationAction.CALENDAR_EVENT_CREATE,
                AuthorizationAction.CALENDAR_EVENT_LIST,
                AuthorizationAction.CALENDAR_EVENT_READ,
                AuthorizationAction.CALENDAR_EVENT_UPDATE,
                AuthorizationAction.CALENDAR_EVENT_CANCEL,
                AuthorizationAction.CALENDAR_TIME_BLOCK_CREATE,
                AuthorizationAction.CALENDAR_TIME_BLOCK_LIST,
                AuthorizationAction.CALENDAR_TIME_BLOCK_READ,
                AuthorizationAction.CALENDAR_TIME_BLOCK_UPDATE,
                AuthorizationAction.CALENDAR_TIME_BLOCK_CANCEL,
                AuthorizationAction.CALENDAR_CONFLICT_READ,
                AuthorizationAction.CALENDAR_OPTIMIZE
        }) {
            AuthorizationActionDescriptor descriptor = registry.find(action).orElseThrow();
            assertThat(descriptor.workloadIdentity())
                    .isEqualTo(AuthorizationActionDescriptorRegistry.CALENDAR_WORKLOAD);
            assertThat(descriptor.tenantScope()).isEqualTo(AuthorizationTenantScope.PERSONAL);
            assertThat(descriptor.ownerRule()).isNotEqualTo(AuthorizationOwnerRule.SUBJECT_OR_TENANT_ADMIN);
        }
        assertDescriptor(
                AuthorizationAction.CALENDAR_EVENT_READ,
                AuthorizationActionDescriptorRegistry.CALENDAR_WORKLOAD,
                "calendar-event",
                AuthorizationResourceShape.OWNED_OBJECT,
                AuthorizationTenantScope.PERSONAL,
                AuthorizationOwnerRule.SUBJECT_ONLY);
        assertDescriptor(
                AuthorizationAction.CALENDAR_TIME_BLOCK_CREATE,
                AuthorizationActionDescriptorRegistry.CALENDAR_WORKLOAD,
                "calendar-time-block",
                AuthorizationResourceShape.OWNED_CREATE,
                AuthorizationTenantScope.PERSONAL,
                AuthorizationOwnerRule.SUBJECT_ONLY);
        assertDescriptor(
                AuthorizationAction.CALENDAR_CONFLICT_READ,
                AuthorizationActionDescriptorRegistry.CALENDAR_WORKLOAD,
                "calendar",
                AuthorizationResourceShape.TENANT_COLLECTION,
                AuthorizationTenantScope.PERSONAL,
                AuthorizationOwnerRule.NONE);
    }

    @Test
    void mapsFinanceAndTrustActionsToSelfOnlyWorkloadFamilies() {
        assertDescriptor(
                AuthorizationAction.FINANCE_TRANSACTION_CATEGORIZE,
                AuthorizationActionDescriptorRegistry.FINANCE_WORKLOAD,
                "finance-transaction",
                AuthorizationResourceShape.OWNED_OBJECT,
                AuthorizationTenantScope.PERSONAL,
                AuthorizationOwnerRule.SUBJECT_ONLY);
        assertDescriptor(
                AuthorizationAction.FINANCE_FORECAST_READ,
                AuthorizationActionDescriptorRegistry.FINANCE_WORKLOAD,
                "finance",
                AuthorizationResourceShape.TENANT_COLLECTION,
                AuthorizationTenantScope.PERSONAL,
                AuthorizationOwnerRule.NONE);
        for (AuthorizationAction action : new AuthorizationAction[] {
                AuthorizationAction.TRUST_DOCUMENT_PROOF_CREATE,
                AuthorizationAction.TRUST_MERKLE_PROOF_CREATE,
                AuthorizationAction.TRUST_PROOF_VERIFY,
                AuthorizationAction.TRUST_ANCHOR_CREATE,
                AuthorizationAction.TRUST_CREDENTIAL_VERIFY,
                AuthorizationAction.TRUST_AI_AUDIT_ANCHOR_CREATE,
                AuthorizationAction.TRUST_GOAL_CERTIFICATE_CREATE
        }) {
            assertDescriptor(
                    action,
                    AuthorizationActionDescriptorRegistry.TRUST_LEDGER_WORKLOAD,
                    "trust-ledger",
                    AuthorizationResourceShape.TENANT_COLLECTION,
                    AuthorizationTenantScope.PERSONAL,
                    AuthorizationOwnerRule.NONE);
        }
    }

    @Test
    void mapsDocumentVaultActionsToTheExactSelfOnlyDocumentContracts() {
        assertDescriptor(
                AuthorizationAction.DOCUMENT_CREATE,
                AuthorizationActionDescriptorRegistry.DOCUMENT_VAULT_WORKLOAD,
                "document",
                AuthorizationResourceShape.OWNED_CREATE,
                AuthorizationTenantScope.PERSONAL,
                AuthorizationOwnerRule.SUBJECT_ONLY);
        for (AuthorizationAction action : new AuthorizationAction[] {
                AuthorizationAction.DOCUMENT_READ, AuthorizationAction.DOCUMENT_UPDATE
        }) {
            assertDescriptor(
                    action,
                    AuthorizationActionDescriptorRegistry.DOCUMENT_VAULT_WORKLOAD,
                    "document",
                    AuthorizationResourceShape.OWNED_OBJECT,
                    AuthorizationTenantScope.PERSONAL,
                    AuthorizationOwnerRule.SUBJECT_ONLY);
        }
        assertDescriptor(
                AuthorizationAction.DOCUMENT_SEARCH,
                AuthorizationActionDescriptorRegistry.DOCUMENT_VAULT_WORKLOAD,
                "document",
                AuthorizationResourceShape.TENANT_COLLECTION,
                AuthorizationTenantScope.PERSONAL,
                AuthorizationOwnerRule.NONE);
    }

    @Test
    void mapsMediaActionsToTheExactSelfOnlyAssetAndSessionContracts() {
        assertDescriptor(
                AuthorizationAction.MEDIA_ASSET_CREATE,
                AuthorizationActionDescriptorRegistry.MEDIA_WORKLOAD,
                "media-asset",
                AuthorizationResourceShape.OWNED_CREATE,
                AuthorizationTenantScope.PERSONAL,
                AuthorizationOwnerRule.SUBJECT_ONLY);
        assertDescriptor(
                AuthorizationAction.MEDIA_ASSET_LIST,
                AuthorizationActionDescriptorRegistry.MEDIA_WORKLOAD,
                "media",
                AuthorizationResourceShape.TENANT_COLLECTION,
                AuthorizationTenantScope.PERSONAL,
                AuthorizationOwnerRule.NONE);
        for (AuthorizationAction action : new AuthorizationAction[] {
                AuthorizationAction.MEDIA_ASSET_READ,
                AuthorizationAction.MEDIA_ASSET_UPLOAD,
                AuthorizationAction.MEDIA_HLS_MANIFEST_READ,
                AuthorizationAction.MEDIA_HLS_SEGMENT_READ
        }) {
            assertDescriptor(
                    action,
                    AuthorizationActionDescriptorRegistry.MEDIA_WORKLOAD,
                    "media-asset",
                    AuthorizationResourceShape.OWNED_OBJECT,
                    AuthorizationTenantScope.PERSONAL,
                    AuthorizationOwnerRule.SUBJECT_ONLY);
        }
        assertDescriptor(
                AuthorizationAction.MEDIA_SESSION_CREATE,
                AuthorizationActionDescriptorRegistry.MEDIA_WORKLOAD,
                "media-session",
                AuthorizationResourceShape.OWNED_CREATE,
                AuthorizationTenantScope.PERSONAL,
                AuthorizationOwnerRule.SUBJECT_ONLY);
        assertDescriptor(
                AuthorizationAction.MEDIA_SESSION_LIST,
                AuthorizationActionDescriptorRegistry.MEDIA_WORKLOAD,
                "media",
                AuthorizationResourceShape.TENANT_COLLECTION,
                AuthorizationTenantScope.PERSONAL,
                AuthorizationOwnerRule.NONE);
        for (AuthorizationAction action : new AuthorizationAction[] {
                AuthorizationAction.MEDIA_SESSION_READ,
                AuthorizationAction.MEDIA_SESSION_UPDATE,
                AuthorizationAction.MEDIA_SESSION_CANCEL,
                AuthorizationAction.MEDIA_SESSION_JOIN
        }) {
            assertDescriptor(
                    action,
                    AuthorizationActionDescriptorRegistry.MEDIA_WORKLOAD,
                    "media-session",
                    AuthorizationResourceShape.OWNED_OBJECT,
                    AuthorizationTenantScope.PERSONAL,
                    AuthorizationOwnerRule.SUBJECT_ONLY);
        }
    }

    @Test
    void rejectsWildcardAndShapeOwnerCombinationsDuringDescriptorConstruction() {
        assertThatThrownBy(() -> new AuthorizationActionDescriptor(
                        AuthorizationAction.TASK_READ,
                        "task-goal-service",
                        "*",
                        AuthorizationResourceShape.OWNED_OBJECT,
                        AuthorizationTenantScope.PERSONAL,
                        AuthorizationOwnerRule.SUBJECT_ONLY))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuthorizationActionDescriptor(
                        AuthorizationAction.TASK_LIST,
                        "task-goal-service",
                        "task",
                        AuthorizationResourceShape.TENANT_COLLECTION,
                        AuthorizationTenantScope.PERSONAL,
                        AuthorizationOwnerRule.SUBJECT_ONLY))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private void assertDescriptor(
            AuthorizationAction action,
            String workload,
            String resourceType,
            AuthorizationResourceShape resourceShape,
            AuthorizationTenantScope tenantScope,
            AuthorizationOwnerRule ownerRule) {
        assertThat(registry.find(action)).hasValue(new AuthorizationActionDescriptor(
                action, workload, resourceType, resourceShape, tenantScope, ownerRule));
    }
}

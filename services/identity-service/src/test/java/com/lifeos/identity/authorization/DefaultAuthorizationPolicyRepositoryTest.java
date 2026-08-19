package com.lifeos.identity.authorization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import org.junit.jupiter.api.Test;

/** Ensures configured policy versions only claim explicit, implemented semantics. */
class DefaultAuthorizationPolicyRepositoryTest {

    @Test
    void supportsExactlyTheImplementedV1AndV2Policies() {
        assertThat(new DefaultAuthorizationPolicyRepository("v1").loadCurrentPolicy().version()).isEqualTo("v1");
        assertThat(new DefaultAuthorizationPolicyRepository("v2").loadCurrentPolicy().version()).isEqualTo("v2");
        assertThatThrownBy(() -> new DefaultAuthorizationPolicyRepository("v3"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unsupported authorization policy version");
    }

    @Test
    void grantsOnlyTheExplicitLegacyActionsInV1() {
        AuthorizationPolicy policy = new DefaultAuthorizationPolicyRepository("v1").loadCurrentPolicy();

        assertThat(policy.allowedRoles().keySet()).containsExactlyInAnyOrderElementsOf(legacyActions());
        assertThat(policy.allowedRoles().values()).allSatisfy(roles ->
                assertThat(roles).containsExactlyInAnyOrder(
                        AuthorizationRole.MEMBER, AuthorizationRole.TENANT_ADMIN));
        assertThat(policy.supports(AuthorizationAction.TASK_READ)).isFalse();
        assertThat(policy.supports(AuthorizationAction.CALENDAR_EVENT_READ)).isFalse();
        assertThat(policy.supports(AuthorizationAction.FINANCE_TRANSACTION_READ)).isFalse();
        assertThat(policy.supports(AuthorizationAction.TRUST_PROOF_VERIFY)).isFalse();
        assertThat(policy.supports(AuthorizationAction.DOCUMENT_READ)).isFalse();
        assertThat(policy.supports(AuthorizationAction.MEDIA_ASSET_READ)).isFalse();
    }

    @Test
    void v2HasAnExplicitRuleForEveryClosedDescriptorAction() {
        AuthorizationPolicy policy = new DefaultAuthorizationPolicyRepository("v2").loadCurrentPolicy();

        assertThat(policy.allowedRoles().keySet()).containsExactlyInAnyOrder(AuthorizationAction.values());
        assertThat(policy.allowedRoles().values()).allSatisfy(roles ->
                assertThat(roles).containsExactlyInAnyOrder(
                        AuthorizationRole.MEMBER, AuthorizationRole.TENANT_ADMIN));
    }

    @Test
    void v2ResolvesOnlyLegacyActionsThroughTheV1CompatibilityView() {
        DefaultAuthorizationPolicyRepository repository = new DefaultAuthorizationPolicyRepository("v2");

        assertThat(repository.findCompatiblePolicy("v1", AuthorizationAction.GOAL_READ))
                .hasValueSatisfying(policy -> {
                    assertThat(policy.version()).isEqualTo("v1");
                    assertThat(policy.supports(AuthorizationAction.GOAL_READ)).isTrue();
                });
        assertThat(repository.findCompatiblePolicy("v1", AuthorizationAction.PROFILE_PRIVACY_READ))
                .hasValueSatisfying(policy -> assertThat(policy.version()).isEqualTo("v1"));
        assertThat(repository.findCompatiblePolicy("v1", AuthorizationAction.TASK_READ)).isEmpty();
        assertThat(repository.findCompatiblePolicy("v1", AuthorizationAction.CALENDAR_EVENT_READ)).isEmpty();
        assertThat(repository.findCompatiblePolicy("v1", AuthorizationAction.FINANCE_TRANSACTION_READ)).isEmpty();
        assertThat(repository.findCompatiblePolicy("v1", AuthorizationAction.TRUST_PROOF_VERIFY)).isEmpty();
        assertThat(repository.findCompatiblePolicy("v1", AuthorizationAction.DOCUMENT_READ)).isEmpty();
        assertThat(repository.findCompatiblePolicy("v1", AuthorizationAction.MEDIA_ASSET_READ)).isEmpty();
        assertThat(repository.findCompatiblePolicy("v2", AuthorizationAction.GOAL_READ)).isEmpty();
    }

    @Test
    void v1DoesNotClaimACompatibilityDowngradeOrUpgrade() {
        DefaultAuthorizationPolicyRepository repository = new DefaultAuthorizationPolicyRepository("v1");

        assertThat(repository.findCompatiblePolicy("v2", AuthorizationAction.GOAL_READ)).isEmpty();
        assertThat(repository.findCompatiblePolicy("v1", AuthorizationAction.GOAL_READ)).isEmpty();
    }

    private Set<AuthorizationAction> legacyActions() {
        return Set.of(
                AuthorizationAction.GOAL_CREATE,
                AuthorizationAction.GOAL_LIST,
                AuthorizationAction.GOAL_READ,
                AuthorizationAction.GOAL_UPDATE,
                AuthorizationAction.GOAL_COMPLETE,
                AuthorizationAction.GOAL_ARCHIVE,
                AuthorizationAction.GOAL_DEPENDENCY_ORDER,
                AuthorizationAction.PROFILE_CREATE,
                AuthorizationAction.PROFILE_READ,
                AuthorizationAction.PROFILE_UPDATE,
                AuthorizationAction.PROFILE_PREFERENCES_READ,
                AuthorizationAction.PROFILE_PREFERENCES_UPDATE,
                AuthorizationAction.PROFILE_PRIVACY_READ,
                AuthorizationAction.PROFILE_PRIVACY_UPDATE,
                AuthorizationAction.PROFILE_AI_PERSONALIZATION_READ,
                AuthorizationAction.PROFILE_AI_PERSONALIZATION_UPDATE,
                AuthorizationAction.HOUSEHOLD_CREATE,
                AuthorizationAction.HOUSEHOLD_READ,
                AuthorizationAction.HOUSEHOLD_MEMBERS_READ,
                AuthorizationAction.HOUSEHOLD_MEMBERS_MANAGE);
    }
}

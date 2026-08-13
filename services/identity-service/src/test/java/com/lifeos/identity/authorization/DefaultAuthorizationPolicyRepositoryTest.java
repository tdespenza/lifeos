package com.lifeos.identity.authorization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Ensures configured policy versions never claim semantics this adapter does not implement. */
class DefaultAuthorizationPolicyRepositoryTest {

    @Test
    void supportsOnlyTheImplementedV1Policy() {
        DefaultAuthorizationPolicyRepository repository = new DefaultAuthorizationPolicyRepository("v1");

        assertThat(repository.loadCurrentPolicy().version()).isEqualTo("v1");
        assertThatThrownBy(() -> new DefaultAuthorizationPolicyRepository("v2"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unsupported authorization policy version");
    }

    @Test
    void grantsOnlyTheExplicitlyConfiguredV1Actions() {
        AuthorizationPolicy policy = new DefaultAuthorizationPolicyRepository().loadCurrentPolicy();

        assertThat(policy.allowedRoles().keySet()).containsExactlyInAnyOrder(
                AuthorizationAction.GOAL_CREATE,
                AuthorizationAction.GOAL_LIST,
                AuthorizationAction.GOAL_READ,
                AuthorizationAction.GOAL_DEPENDENCY_ORDER);
        assertThat(policy.allowedRoles().values()).allSatisfy(roles ->
                assertThat(roles).containsExactlyInAnyOrder(
                        AuthorizationRole.MEMBER, AuthorizationRole.TENANT_ADMIN));
    }
}

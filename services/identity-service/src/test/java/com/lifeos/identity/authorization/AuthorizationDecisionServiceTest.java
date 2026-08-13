package com.lifeos.identity.authorization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.lifeos.identity.account.UserAccount;
import com.lifeos.identity.account.UserAccountRepository;
import com.lifeos.identity.auth.AuthenticatedSubject;
import com.lifeos.identity.auth.AuthSession;
import com.lifeos.identity.auth.AuthSessionRepository;
import com.lifeos.identity.auth.JwtValidationService;
import com.lifeos.identity.auth.SessionAuthenticationMethod;
import com.lifeos.identity.auth.TokenDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.util.ReflectionTestUtils;

/** Decision-table tests for deterministic RBAC and ABAC behavior. */
@ExtendWith(MockitoExtension.class)
class AuthorizationDecisionServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-13T12:00:00Z");

    @Mock
    private AuthSessionRepository sessionRepository;

    @Mock
    private JwtDecoder jwtDecoder;

    @Mock
    private UserAccountRepository accountRepository;

    @Mock
    private AuthorizationMembershipRepository membershipRepository;

    @Mock
    private AuthorizationPolicyRepository policyRepository;

    private AuthorizationDecisionService service;
    private UUID subjectId;
    private UUID sessionId;

    @BeforeEach
    void setUp() {
        subjectId = UUID.randomUUID();
        sessionId = UUID.randomUUID();
        service = new AuthorizationDecisionService(
                sessionRepository,
                accountRepository,
                membershipRepository,
                policyRepository,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void allowsMemberToReadOwnGoalInPersonalTenant() {
        activeMember();
        AuthorizationDecision decision = service.decide(request(
                "goal:read",
                subjectId.toString(),
                UUID.randomUUID().toString(),
                subjectId.toString(),
                "v1"));

        assertThat(decision.outcome()).isEqualTo(DecisionOutcome.ALLOW);
        assertThat(decision.reasonCode()).isEqualTo("ALLOWED");
        assertThat(decision.policyVersion()).isEqualTo("v1");
        assertThat(decision.expiresAt()).isEqualTo(NOW.plusSeconds(300));
    }

    @Test
    void allowsMemberToCreateOnlyAnOwnedGoalInTheirPersonalTenant() {
        activeMember();
        AuthorizationDecision allowed = service.decide(new AuthorizationRequest(
                subjectId,
                sessionId,
                proofFor(sessionId),
                AuthorizationAction.GOAL_CREATE.value(),
                new AuthorizationResource(
                        "goal",
                        UUID.randomUUID().toString(),
                        subjectId.toString(),
                        Map.of("ownerAccountId", subjectId.toString())),
                "v1"));
        assertThat(allowed.outcome()).isEqualTo(DecisionOutcome.ALLOW);

        AuthorizationDecision denied = service.decide(new AuthorizationRequest(
                subjectId,
                sessionId,
                proofFor(sessionId),
                AuthorizationAction.GOAL_CREATE.value(),
                new AuthorizationResource(
                        "goal",
                        UUID.randomUUID().toString(),
                        subjectId.toString(),
                        Map.of("ownerAccountId", UUID.randomUUID().toString())),
                "v1"));
        assertDeny(denied, AuthorizationDenyReason.OWNER_MISMATCH);
    }

    @Test
    void deniesMissingRoleEvenWhenOtherRequestFactsMatch() {
        activeSubject(subjectId, sessionId, NOW.plusSeconds(300));
        when(policyRepository.loadCurrentPolicy()).thenReturn(policyWithOnlyTenantAdmins());
        when(membershipRepository.findByAccountIdAndTenantIdAndActiveTrue(any(), any()))
                .thenReturn(List.of());

        AuthorizationDecision decision = service.decide(request(
                "goal:read",
                subjectId.toString(),
                UUID.randomUUID().toString(),
                subjectId.toString(),
                "v1"));

        assertDeny(decision, AuthorizationDenyReason.MISSING_ROLE);
    }

    @Test
    void deniesFailedOwnerAttributeAndCrossUserGoal() {
        activeMember();
        AuthorizationDecision decision = service.decide(request(
                "goal:read",
                subjectId.toString(),
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                "v1"));

        assertDeny(decision, AuthorizationDenyReason.OWNER_MISMATCH);
    }

    @Test
    void deniesStaleRevokedExpiredAndDisabledSubjects() {
        AuthSession revoked = session(subjectId, sessionId, NOW.plusSeconds(300));
        revoked.revoke();
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(revoked));
        assertDeny(service.decide(ownReadRequest()), AuthorizationDenyReason.STALE_SUBJECT);

        activeSubject(subjectId, sessionId, NOW.minusSeconds(1));
        assertDeny(service.decide(ownReadRequest()), AuthorizationDenyReason.STALE_SUBJECT);

        activeSubject(subjectId, sessionId, NOW.plusSeconds(300));
        UserAccount disabled = account(subjectId);
        disabled.disable();
        when(accountRepository.findById(subjectId)).thenReturn(Optional.of(disabled));
        assertDeny(service.decide(ownReadRequest()), AuthorizationDenyReason.STALE_SUBJECT);
    }

    @Test
    void deniesAuthorizationWhenTheAccessTokenRotatesAfterValidation() {
        String validatedRawToken = "validated-access-token";
        Instant durableExpiry = Instant.parse("2099-01-01T00:00:00Z");
        AuthSession durableSession = new AuthSession(
                sessionId,
                account(subjectId),
                SessionAuthenticationMethod.PASSWORD,
                TokenDigest.sha256(validatedRawToken),
                NOW.minusSeconds(30),
                durableExpiry);
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(durableSession));
        when(jwtDecoder.decode(validatedRawToken)).thenReturn(Jwt.withTokenValue(validatedRawToken)
                .header("alg", "HS256")
                .claim("sub", subjectId.toString())
                .claim("session_id", sessionId.toString())
                .issuedAt(NOW.minusSeconds(30))
                .expiresAt(durableExpiry)
                .build());

        AuthenticatedSubject validated = new JwtValidationService(jwtDecoder, sessionRepository)
                .validate(validatedRawToken);

        durableSession.replaceAccessTokenHash(TokenDigest.sha256("rotated-successor-access-token"));
        AuthorizationDecision decision = service.decide(new AuthorizationRequest(
                validated.accountId(),
                validated.sessionId(),
                validated.accessTokenProof(),
                AuthorizationAction.GOAL_LIST.value(),
                new AuthorizationResource("goal", null, subjectId.toString(), Map.of()),
                "v1"));

        assertDeny(decision, AuthorizationDenyReason.STALE_SUBJECT);
    }

    @Test
    void deniesPolicyVersionMismatchAndUnknownActionDeterministically() {
        activeSubject(subjectId, sessionId, NOW.plusSeconds(300));
        when(policyRepository.loadCurrentPolicy()).thenReturn(v1Policy());
        AuthorizationDecision versionMismatch = service.decide(request(
                "goal:read",
                subjectId.toString(),
                UUID.randomUUID().toString(),
                subjectId.toString(),
                "older-version"));
        assertDeny(versionMismatch, AuthorizationDenyReason.POLICY_VERSION_MISMATCH);

        AuthorizationDecision unknownAction = service.decide(request(
                "goal:delete",
                subjectId.toString(),
                UUID.randomUUID().toString(),
                subjectId.toString(),
                "v1"));
        assertDeny(unknownAction, AuthorizationDenyReason.UNSUPPORTED_ACTION);
    }

    @Test
    void allowsExplicitTenantAdminToReadAnotherOwnerInItsScopedTenant() {
        String sharedTenant = "household-42";
        UUID otherOwner = UUID.randomUUID();
        activeSubject(subjectId, sessionId, NOW.plusSeconds(300));
        when(policyRepository.loadCurrentPolicy()).thenReturn(v1Policy());
        when(membershipRepository.findByAccountIdAndTenantIdAndActiveTrue(subjectId, sharedTenant))
                .thenReturn(List.of(new AuthorizationMembership(
                        subjectId, sharedTenant, AuthorizationRole.TENANT_ADMIN)));

        AuthorizationDecision decision = service.decide(request(
                "goal:read",
                sharedTenant,
                UUID.randomUUID().toString(),
                otherOwner.toString(),
                "v1"));

        assertThat(decision.outcome()).isEqualTo(DecisionOutcome.ALLOW);
    }

    @Test
    void deniesMissingGoalFactsEvenForAScopedTenantAdmin() {
        String sharedTenant = "household-42";
        activeSubject(subjectId, sessionId, NOW.plusSeconds(300));
        when(policyRepository.loadCurrentPolicy()).thenReturn(v1Policy());
        when(membershipRepository.findByAccountIdAndTenantIdAndActiveTrue(subjectId, sharedTenant))
                .thenReturn(List.of(new AuthorizationMembership(
                        subjectId, sharedTenant, AuthorizationRole.TENANT_ADMIN)));

        AuthorizationDecision decision = service.decide(new AuthorizationRequest(
                subjectId,
                sessionId,
                proofFor(sessionId),
                AuthorizationAction.GOAL_READ.value(),
                new AuthorizationResource(
                        "goal",
                        UUID.randomUUID().toString(),
                        sharedTenant,
                        Map.of(
                                "ownerAccountId", "00000000-0000-0000-0000-000000000000",
                                "resourceExists", "false")),
                "v1"));

        assertDeny(decision, AuthorizationDenyReason.OWNER_MISMATCH);
    }

    @Test
    void allowsCollectionActionsWithTenantScopeAndNoOwnerAttribute() {
        activeMember();

        AuthorizationDecision list = service.decide(collectionRequest(
                AuthorizationAction.GOAL_LIST.value(), subjectId.toString()));
        AuthorizationDecision dependencyOrder = service.decide(collectionRequest(
                AuthorizationAction.GOAL_DEPENDENCY_ORDER.value(), subjectId.toString()));

        assertThat(list.outcome()).isEqualTo(DecisionOutcome.ALLOW);
        assertThat(dependencyOrder.outcome()).isEqualTo(DecisionOutcome.ALLOW);
    }

    @Test
    void allowsScopedTenantAdminCollectionActionsWithoutAnOwnerAttribute() {
        String sharedTenant = "household-42";
        activeSubject(subjectId, sessionId, NOW.plusSeconds(300));
        when(policyRepository.loadCurrentPolicy()).thenReturn(v1Policy());
        when(membershipRepository.findByAccountIdAndTenantIdAndActiveTrue(subjectId, sharedTenant))
                .thenReturn(List.of(new AuthorizationMembership(
                        subjectId, sharedTenant, AuthorizationRole.TENANT_ADMIN)));

        AuthorizationDecision decision = service.decide(collectionRequest(
                AuthorizationAction.GOAL_LIST.value(), sharedTenant));

        assertThat(decision.outcome()).isEqualTo(DecisionOutcome.ALLOW);
    }

    @Test
    void treatsPolicyAndPersistenceFailuresAsFailClosedDependencyDenials() {
        activeSubject(subjectId, sessionId, NOW.plusSeconds(300));
        when(policyRepository.loadCurrentPolicy()).thenThrow(new IllegalStateException("policy unavailable"));

        assertDeny(service.decide(ownReadRequest()), AuthorizationDenyReason.POLICY_UNAVAILABLE);

        org.mockito.Mockito.reset(policyRepository);
        when(policyRepository.loadCurrentPolicy()).thenReturn(v1Policy());
        when(membershipRepository.findByAccountIdAndTenantIdAndActiveTrue(any(), any()))
                .thenThrow(new IllegalStateException("database unavailable"));

        assertDeny(service.decide(ownReadRequest()), AuthorizationDenyReason.POLICY_UNAVAILABLE);
    }

    @Test
    void rejectsMalformedResourceFactsBeforePolicyCanAllowThem() {
        AuthorizationRequest malformed = new AuthorizationRequest(
                subjectId,
                sessionId,
                proofFor(sessionId),
                "goal:read",
                new AuthorizationResource("goal", null, subjectId.toString(), Map.of("ownerAccountId", subjectId.toString())),
                "v1");

        assertDeny(service.decide(malformed), AuthorizationDenyReason.MALFORMED_REQUEST);
    }

    @Test
    void rejectsNonUuidGoalResourceIdsBeforePolicyCanAllowThem() {
        AuthorizationDecision decision = service.decide(request(
                "goal:read",
                subjectId.toString(),
                "not-a-goal-uuid",
                subjectId.toString(),
                "v1"));

        assertDeny(decision, AuthorizationDenyReason.MALFORMED_REQUEST);
    }

    @Test
    void onlyAttributesAResultWhenTheDurableSubjectWasVerified() {
        AuthorizationDecisionEvaluation stale = service.decideForAudit(new AuthorizationRequest(
                UUID.randomUUID(),
                sessionId,
                proofFor(sessionId),
                "goal:read",
                new AuthorizationResource(
                        "goal",
                        UUID.randomUUID().toString(),
                        subjectId.toString(),
                        Map.of("ownerAccountId", subjectId.toString(), "resourceExists", "true")),
                "v1"));
        assertThat(stale.decision().reasonCode()).isEqualTo(AuthorizationDenyReason.STALE_SUBJECT.name());
        assertThat(stale.verifiedSubjectId()).isNull();

        activeMember();
        AuthorizationDecisionEvaluation allowed = service.decideForAudit(ownReadRequest());
        assertThat(allowed.verifiedSubjectId()).isEqualTo(subjectId);
    }

    private AuthorizationRequest ownReadRequest() {
        return request(
                "goal:read",
                subjectId.toString(),
                UUID.randomUUID().toString(),
                subjectId.toString(),
                "v1");
    }

    private AuthorizationRequest collectionRequest(String action, String tenantId) {
        return new AuthorizationRequest(
                subjectId,
                sessionId,
                proofFor(sessionId),
                action,
                new AuthorizationResource("goal", null, tenantId, Map.of()),
                "v1");
    }

    private AuthorizationRequest request(
            String action, String tenantId, String resourceId, String ownerAccountId, String expectedVersion) {
        return new AuthorizationRequest(
                subjectId,
                sessionId,
                proofFor(sessionId),
                action,
                new AuthorizationResource(
                        "goal",
                        resourceId,
                        tenantId,
                        Map.of("ownerAccountId", ownerAccountId, "resourceExists", "true")),
                expectedVersion);
    }

    private void activeSubject(UUID accountId, UUID session, Instant expiresAt) {
        when(sessionRepository.findById(session)).thenReturn(Optional.of(session(accountId, session, expiresAt)));
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account(accountId)));
    }

    private void activeMember() {
        activeSubject(subjectId, sessionId, NOW.plusSeconds(300));
        when(policyRepository.loadCurrentPolicy()).thenReturn(v1Policy());
        when(membershipRepository.findByAccountIdAndTenantIdAndActiveTrue(any(), any()))
                .thenReturn(List.of());
    }

    private AuthSession session(UUID accountId, UUID id, Instant expiresAt) {
        return new AuthSession(
                id,
                account(accountId),
                SessionAuthenticationMethod.PASSWORD,
                proofFor(id),
                NOW.minusSeconds(30),
                expiresAt);
    }

    private String proofFor(UUID session) {
        return TokenDigest.sha256("test-token-" + session);
    }

    private UserAccount account(UUID id) {
        UserAccount account = new UserAccount("ada@example.com", "Ada Lovelace");
        ReflectionTestUtils.setField(account, "id", id);
        return account;
    }

    private AuthorizationPolicy v1Policy() {
        return new AuthorizationPolicy("v1", Map.of(
                AuthorizationAction.GOAL_CREATE, EnumSet.of(AuthorizationRole.MEMBER, AuthorizationRole.TENANT_ADMIN),
                AuthorizationAction.GOAL_LIST, EnumSet.of(AuthorizationRole.MEMBER, AuthorizationRole.TENANT_ADMIN),
                AuthorizationAction.GOAL_READ, EnumSet.of(AuthorizationRole.MEMBER, AuthorizationRole.TENANT_ADMIN),
                AuthorizationAction.GOAL_DEPENDENCY_ORDER,
                EnumSet.of(AuthorizationRole.MEMBER, AuthorizationRole.TENANT_ADMIN)));
    }

    private AuthorizationPolicy policyWithOnlyTenantAdmins() {
        return new AuthorizationPolicy("v1", Map.of(
                AuthorizationAction.GOAL_CREATE, EnumSet.of(AuthorizationRole.TENANT_ADMIN),
                AuthorizationAction.GOAL_LIST, EnumSet.of(AuthorizationRole.TENANT_ADMIN),
                AuthorizationAction.GOAL_READ, EnumSet.of(AuthorizationRole.TENANT_ADMIN),
                AuthorizationAction.GOAL_DEPENDENCY_ORDER, EnumSet.of(AuthorizationRole.TENANT_ADMIN)));
    }

    private void assertDeny(AuthorizationDecision decision, AuthorizationDenyReason reason) {
        assertThat(decision.outcome()).isEqualTo(DecisionOutcome.DENY);
        assertThat(decision.reasonCode()).isEqualTo(reason.name());
    }
}

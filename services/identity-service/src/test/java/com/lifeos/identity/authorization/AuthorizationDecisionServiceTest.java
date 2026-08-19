package com.lifeos.identity.authorization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
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
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
    void appliesTheSameOwnerAndTenantAbacBoundaryToEveryGoalLifecycleMutation() {
        activeMember();
        for (AuthorizationAction action : List.of(
                AuthorizationAction.GOAL_UPDATE,
                AuthorizationAction.GOAL_COMPLETE,
                AuthorizationAction.GOAL_ARCHIVE)) {
            AuthorizationDecision own = service.decide(request(
                    action.value(),
                    subjectId.toString(),
                    UUID.randomUUID().toString(),
                    subjectId.toString(),
                    "v1"));
            AuthorizationDecision otherOwner = service.decide(request(
                    action.value(),
                    subjectId.toString(),
                    UUID.randomUUID().toString(),
                    UUID.randomUUID().toString(),
                    "v1"));

            assertThat(own.outcome()).isEqualTo(DecisionOutcome.ALLOW);
            assertDeny(otherOwner, AuthorizationDenyReason.OWNER_MISMATCH);
        }
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
    void allowsOnlyTheSubjectToAccessAnExistingPersonalProfile() {
        activeMember();
        UUID profileId = UUID.randomUUID();
        AuthorizationDecision own = service.decide(new AuthorizationRequest(
                subjectId,
                sessionId,
                proofFor(sessionId),
                AuthorizationAction.PROFILE_PRIVACY_READ.value(),
                new AuthorizationResource(
                        "profile",
                        profileId.toString(),
                        subjectId.toString(),
                        Map.of("ownerAccountId", subjectId.toString(), "resourceExists", "true")),
                "v1"));
        AuthorizationDecision otherOwner = service.decide(new AuthorizationRequest(
                subjectId,
                sessionId,
                proofFor(sessionId),
                AuthorizationAction.PROFILE_PRIVACY_READ.value(),
                new AuthorizationResource(
                        "profile",
                        profileId.toString(),
                        subjectId.toString(),
                        Map.of("ownerAccountId", UUID.randomUUID().toString(), "resourceExists", "true")),
                "v1"));

        assertThat(own.outcome()).isEqualTo(DecisionOutcome.ALLOW);
        assertDeny(otherOwner, AuthorizationDenyReason.OWNER_MISMATCH);
    }

    @Test
    void householdCapabilityRequiresTheAuthenticatedSelfTenantAndRequesterFacts() {
        activeMember();
        UUID householdId = UUID.randomUUID();
        AuthorizationDecision allowed = service.decide(new AuthorizationRequest(
                subjectId,
                sessionId,
                proofFor(sessionId),
                AuthorizationAction.HOUSEHOLD_MEMBERS_MANAGE.value(),
                new AuthorizationResource(
                        "household",
                        householdId.toString(),
                        subjectId.toString(),
                        Map.of("requesterAccountId", subjectId.toString())),
                "v1"));
        AuthorizationDecision crossRequester = service.decide(new AuthorizationRequest(
                subjectId,
                sessionId,
                proofFor(sessionId),
                AuthorizationAction.HOUSEHOLD_MEMBERS_MANAGE.value(),
                new AuthorizationResource(
                        "household",
                        householdId.toString(),
                        subjectId.toString(),
                        Map.of("requesterAccountId", UUID.randomUUID().toString())),
                "v1"));
        AuthorizationDecision malformed = service.decide(new AuthorizationRequest(
                subjectId,
                sessionId,
                proofFor(sessionId),
                AuthorizationAction.HOUSEHOLD_MEMBERS_MANAGE.value(),
                new AuthorizationResource(
                        "household", householdId.toString(), subjectId.toString(), Map.of("ownerAccountId", subjectId.toString())),
                "v1"));

        assertThat(allowed.outcome()).isEqualTo(DecisionOutcome.ALLOW);
        assertDeny(crossRequester, AuthorizationDenyReason.OWNER_MISMATCH);
        assertDeny(malformed, AuthorizationDenyReason.MALFORMED_REQUEST);
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
        UserAccount durableAccount = account(subjectId);
        AuthSession durableSession = new AuthSession(
                sessionId,
                durableAccount,
                SessionAuthenticationMethod.PASSWORD,
                TokenDigest.sha256(validatedRawToken),
                NOW.minusSeconds(30),
                durableExpiry);
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(durableSession));
        when(sessionRepository.touchLastUsedAt(any(), any())).thenReturn(1);
        when(accountRepository.findById(subjectId)).thenReturn(Optional.of(durableAccount));
        when(policyRepository.loadCurrentPolicy()).thenReturn(v1Policy());
        when(membershipRepository.findByAccountIdAndTenantIdAndActiveTrue(subjectId, subjectId.toString()))
                .thenReturn(List.of());
        when(jwtDecoder.decode(validatedRawToken)).thenReturn(Jwt.withTokenValue(validatedRawToken)
                .header("alg", "HS256")
                .claim("sub", subjectId.toString())
                .claim("session_id", sessionId.toString())
                .issuedAt(NOW.minusSeconds(30))
                .expiresAt(durableExpiry)
                .build());

        AuthenticatedSubject validated = new JwtValidationService(jwtDecoder, sessionRepository)
                .validate(validatedRawToken);

        AuthorizationRequest request = new AuthorizationRequest(
                validated.accountId(),
                validated.sessionId(),
                validated.accessTokenProof(),
                AuthorizationAction.GOAL_LIST.value(),
                new AuthorizationResource("goal", null, subjectId.toString(), Map.of()),
                "v1");
        assertThat(service.decide(request).outcome()).isEqualTo(DecisionOutcome.ALLOW);
        verify(membershipRepository)
                .findByAccountIdAndTenantIdAndActiveTrue(subjectId, subjectId.toString());

        durableSession.replaceAccessTokenHash(TokenDigest.sha256("rotated-successor-access-token"));
        AuthorizationDecision decision = service.decide(request);

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
    void allowsExplicitTenantAdminToOperateOnAnotherOwnerInItsScopedTenant() {
        String sharedTenant = "household-42";
        UUID otherOwner = UUID.randomUUID();
        activeSubject(subjectId, sessionId, NOW.plusSeconds(300));
        when(policyRepository.loadCurrentPolicy()).thenReturn(v1Policy());
        when(membershipRepository.findByAccountIdAndTenantIdAndActiveTrue(subjectId, sharedTenant))
                .thenReturn(List.of(new AuthorizationMembership(
                        subjectId, sharedTenant, AuthorizationRole.TENANT_ADMIN)));

        for (AuthorizationAction action : List.of(
                AuthorizationAction.GOAL_READ,
                AuthorizationAction.GOAL_UPDATE,
                AuthorizationAction.GOAL_COMPLETE,
                AuthorizationAction.GOAL_ARCHIVE)) {
            AuthorizationDecision decision = service.decide(request(
                    action.value(),
                    sharedTenant,
                    UUID.randomUUID().toString(),
                    otherOwner.toString(),
                    "v1"));

            assertThat(decision.outcome()).isEqualTo(DecisionOutcome.ALLOW);
        }
    }

    @Test
    void deniesMissingGoalFactsForEveryObjectActionEvenForAScopedTenantAdmin() {
        String sharedTenant = "household-42";
        activeSubject(subjectId, sessionId, NOW.plusSeconds(300));
        when(policyRepository.loadCurrentPolicy()).thenReturn(v1Policy());
        when(membershipRepository.findByAccountIdAndTenantIdAndActiveTrue(subjectId, sharedTenant))
                .thenReturn(List.of(new AuthorizationMembership(
                        subjectId, sharedTenant, AuthorizationRole.TENANT_ADMIN)));

        for (AuthorizationAction action : List.of(
                AuthorizationAction.GOAL_READ,
                AuthorizationAction.GOAL_UPDATE,
                AuthorizationAction.GOAL_COMPLETE,
                AuthorizationAction.GOAL_ARCHIVE)) {
            AuthorizationDecision decision = service.decide(new AuthorizationRequest(
                    subjectId,
                    sessionId,
                    proofFor(sessionId),
                    action.value(),
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

    @Test
    void keepsLegacyV1ResponseCompatibilityWhenV2IsActiveForALegacyGoalAction() {
        activeSubject(subjectId, sessionId, NOW.plusSeconds(300));
        when(policyRepository.loadCurrentPolicy()).thenReturn(v2Policy());
        when(policyRepository.findCompatiblePolicy("v1", AuthorizationAction.GOAL_READ))
                .thenReturn(Optional.of(v1Policy()));
        when(membershipRepository.findByAccountIdAndTenantIdAndActiveTrue(any(), any()))
                .thenReturn(List.of());

        AuthorizationDecision decision = service.decide(ownReadRequest(),
                AuthorizationActionDescriptorRegistry.TASK_GOAL_WORKLOAD);

        assertThat(decision.outcome()).isEqualTo(DecisionOutcome.ALLOW);
        assertThat(decision.policyVersion()).isEqualTo("v1");
        verify(policyRepository).findCompatiblePolicy("v1", AuthorizationAction.GOAL_READ);
    }

    @Test
    void keepsLegacyV1ProfileAndHouseholdResponsesCompatibleWhenV2IsActive() {
        activeSubject(subjectId, sessionId, NOW.plusSeconds(300));
        when(policyRepository.loadCurrentPolicy()).thenReturn(v2Policy());
        when(policyRepository.findCompatiblePolicy("v1", AuthorizationAction.PROFILE_PRIVACY_READ))
                .thenReturn(Optional.of(v1Policy()));
        when(policyRepository.findCompatiblePolicy("v1", AuthorizationAction.HOUSEHOLD_MEMBERS_READ))
                .thenReturn(Optional.of(v1Policy()));
        when(membershipRepository.findByAccountIdAndTenantIdAndActiveTrue(any(), any()))
                .thenReturn(List.of());

        AuthorizationDecision profileDecision = service.decide(new AuthorizationRequest(
                subjectId,
                sessionId,
                proofFor(sessionId),
                AuthorizationAction.PROFILE_PRIVACY_READ.value(),
                new AuthorizationResource(
                        "profile",
                        UUID.randomUUID().toString(),
                        subjectId.toString(),
                        Map.of("ownerAccountId", subjectId.toString(), "resourceExists", "true")),
                "v1"), AuthorizationActionDescriptorRegistry.PROFILE_WORKLOAD);
        AuthorizationDecision householdDecision = service.decide(new AuthorizationRequest(
                subjectId,
                sessionId,
                proofFor(sessionId),
                AuthorizationAction.HOUSEHOLD_MEMBERS_READ.value(),
                new AuthorizationResource(
                        "household",
                        UUID.randomUUID().toString(),
                        subjectId.toString(),
                        Map.of("requesterAccountId", subjectId.toString())),
                "v1"), AuthorizationActionDescriptorRegistry.PROFILE_WORKLOAD);

        assertThat(profileDecision.outcome()).isEqualTo(DecisionOutcome.ALLOW);
        assertThat(profileDecision.policyVersion()).isEqualTo("v1");
        assertThat(householdDecision.outcome()).isEqualTo(DecisionOutcome.ALLOW);
        assertThat(householdDecision.policyVersion()).isEqualTo("v1");
    }

    @Test
    void failsClosedWhenACompatibilityRepositoryReturnsTheWrongResponseVersion() {
        activeSubject(subjectId, sessionId, NOW.plusSeconds(300));
        when(policyRepository.loadCurrentPolicy()).thenReturn(v2Policy());
        when(policyRepository.findCompatiblePolicy("v1", AuthorizationAction.GOAL_READ))
                .thenReturn(Optional.of(v2Policy()));

        AuthorizationDecision decision = service.decide(ownReadRequest(),
                AuthorizationActionDescriptorRegistry.TASK_GOAL_WORKLOAD);

        assertDeny(decision, AuthorizationDenyReason.POLICY_VERSION_MISMATCH);
        assertThat(decision.policyVersion()).isEqualTo("v2");
    }

    @Test
    void rejectsAV2ActionWhenTheCallerRequestsLegacyV1Semantics() {
        activeSubject(subjectId, sessionId, NOW.plusSeconds(300));
        when(policyRepository.loadCurrentPolicy()).thenReturn(v2Policy());
        when(policyRepository.findCompatiblePolicy("v1", AuthorizationAction.TASK_READ))
                .thenReturn(Optional.empty());

        AuthorizationDecision decision = service.decide(new AuthorizationRequest(
                subjectId,
                sessionId,
                proofFor(sessionId),
                AuthorizationAction.TASK_READ.value(),
                new AuthorizationResource(
                        "task",
                        UUID.randomUUID().toString(),
                        subjectId.toString(),
                        Map.of("ownerAccountId", subjectId.toString(), "resourceExists", "true")),
                "v1"), AuthorizationActionDescriptorRegistry.TASK_GOAL_WORKLOAD);

        assertDeny(decision, AuthorizationDenyReason.POLICY_VERSION_MISMATCH);
        assertThat(decision.policyVersion()).isEqualTo("v2");
    }

    @Test
    void v1RejectsNewClosedActionsRatherThanSilentlyGrantingThem() {
        activeSubject(subjectId, sessionId, NOW.plusSeconds(300));
        when(policyRepository.loadCurrentPolicy()).thenReturn(v1Policy());

        AuthorizationDecision decision = service.decide(new AuthorizationRequest(
                subjectId,
                sessionId,
                proofFor(sessionId),
                AuthorizationAction.TASK_READ.value(),
                new AuthorizationResource(
                        "task",
                        UUID.randomUUID().toString(),
                        subjectId.toString(),
                        Map.of("ownerAccountId", subjectId.toString(), "resourceExists", "true")),
                "v1"), AuthorizationActionDescriptorRegistry.TASK_GOAL_WORKLOAD);

        assertDeny(decision, AuthorizationDenyReason.UNSUPPORTED_ACTION);
        assertThat(decision.policyVersion()).isEqualTo("v1");
    }

    @Test
    void bindsCalendarActionsToTheCalendarWorkloadAndSelfOnlyPersonalFacts() {
        activeMemberV2();
        UUID eventId = UUID.randomUUID();
        AuthorizationRequest ownEvent = new AuthorizationRequest(
                subjectId,
                sessionId,
                proofFor(sessionId),
                AuthorizationAction.CALENDAR_EVENT_READ.value(),
                new AuthorizationResource(
                        "calendar-event",
                        eventId.toString(),
                        subjectId.toString(),
                        Map.of("ownerAccountId", subjectId.toString(), "resourceExists", "true")),
                "v2");

        assertThat(service.decide(ownEvent, AuthorizationActionDescriptorRegistry.CALENDAR_WORKLOAD).outcome())
                .isEqualTo(DecisionOutcome.ALLOW);
        assertDeny(
                service.decide(ownEvent, AuthorizationActionDescriptorRegistry.TASK_GOAL_WORKLOAD),
                AuthorizationDenyReason.WORKLOAD_NOT_AUTHORIZED);

        when(membershipRepository.findByAccountIdAndTenantIdAndActiveTrue(subjectId, subjectId.toString()))
                .thenReturn(List.of(new AuthorizationMembership(
                        subjectId, subjectId.toString(), AuthorizationRole.TENANT_ADMIN)));
        AuthorizationRequest otherOwner = new AuthorizationRequest(
                subjectId,
                sessionId,
                proofFor(sessionId),
                AuthorizationAction.CALENDAR_EVENT_READ.value(),
                new AuthorizationResource(
                        "calendar-event",
                        eventId.toString(),
                        subjectId.toString(),
                        Map.of("ownerAccountId", UUID.randomUUID().toString(), "resourceExists", "true")),
                "v2");

        assertDeny(
                service.decide(otherOwner, AuthorizationActionDescriptorRegistry.CALENDAR_WORKLOAD),
                AuthorizationDenyReason.OWNER_MISMATCH);
    }

    @Test
    void acceptsTheTaskDependencyGraphOnlyAsASelfOnlyCollection() {
        activeMemberV2();
        AuthorizationRequest graphRequest = new AuthorizationRequest(
                subjectId,
                sessionId,
                proofFor(sessionId),
                AuthorizationAction.TASK_DEPENDENCY_MANAGE.value(),
                new AuthorizationResource("task-dependency-graph", null, subjectId.toString(), Map.of()),
                "v2");

        assertThat(service.decide(graphRequest, AuthorizationActionDescriptorRegistry.TASK_GOAL_WORKLOAD).outcome())
                .isEqualTo(DecisionOutcome.ALLOW);

        AuthorizationRequest malformedObjectRequest = new AuthorizationRequest(
                subjectId,
                sessionId,
                proofFor(sessionId),
                AuthorizationAction.TASK_DEPENDENCY_MANAGE.value(),
                new AuthorizationResource(
                        "task",
                        UUID.randomUUID().toString(),
                        subjectId.toString(),
                        Map.of("ownerAccountId", subjectId.toString(), "resourceExists", "true")),
                "v2");

        assertDeny(
                service.decide(malformedObjectRequest, AuthorizationActionDescriptorRegistry.TASK_GOAL_WORKLOAD),
                AuthorizationDenyReason.MALFORMED_REQUEST);
    }

    @Test
    void bindsFinanceObjectsAndCollectionsToTheFinanceWorkloadAndPersonalTenant() {
        activeMemberV2();
        AuthorizationRequest ownTransaction = new AuthorizationRequest(
                subjectId,
                sessionId,
                proofFor(sessionId),
                AuthorizationAction.FINANCE_TRANSACTION_CATEGORIZE.value(),
                new AuthorizationResource(
                        "finance-transaction",
                        UUID.randomUUID().toString(),
                        subjectId.toString(),
                        Map.of("ownerAccountId", subjectId.toString(), "resourceExists", "true")),
                "v2");
        AuthorizationRequest forecast = new AuthorizationRequest(
                subjectId,
                sessionId,
                proofFor(sessionId),
                AuthorizationAction.FINANCE_FORECAST_READ.value(),
                new AuthorizationResource("finance", null, subjectId.toString(), Map.of()),
                "v2");

        assertThat(service.decide(ownTransaction, AuthorizationActionDescriptorRegistry.FINANCE_WORKLOAD).outcome())
                .isEqualTo(DecisionOutcome.ALLOW);
        assertThat(service.decide(forecast, AuthorizationActionDescriptorRegistry.FINANCE_WORKLOAD).outcome())
                .isEqualTo(DecisionOutcome.ALLOW);

        AuthorizationRequest crossTenantForecast = new AuthorizationRequest(
                subjectId,
                sessionId,
                proofFor(sessionId),
                AuthorizationAction.FINANCE_FORECAST_READ.value(),
                new AuthorizationResource("finance", null, "shared-tenant", Map.of()),
                "v2");
        assertDeny(
                service.decide(crossTenantForecast, AuthorizationActionDescriptorRegistry.FINANCE_WORKLOAD),
                AuthorizationDenyReason.TENANT_MISMATCH);
    }

    @Test
    void bindsTrustLedgerActionsToTheTrustWorkloadAndExactPersonalCollectionShape() {
        activeMemberV2();
        AuthorizationRequest request = new AuthorizationRequest(
                subjectId,
                sessionId,
                proofFor(sessionId),
                AuthorizationAction.TRUST_DOCUMENT_PROOF_CREATE.value(),
                new AuthorizationResource("trust-ledger", null, subjectId.toString(), Map.of()),
                "v2");

        assertThat(service.decide(request, AuthorizationActionDescriptorRegistry.TRUST_LEDGER_WORKLOAD).outcome())
                .isEqualTo(DecisionOutcome.ALLOW);
        assertDeny(
                service.decide(request, AuthorizationActionDescriptorRegistry.FINANCE_WORKLOAD),
                AuthorizationDenyReason.WORKLOAD_NOT_AUTHORIZED);

        AuthorizationRequest withClientSuppliedFact = new AuthorizationRequest(
                subjectId,
                sessionId,
                proofFor(sessionId),
                AuthorizationAction.TRUST_DOCUMENT_PROOF_CREATE.value(),
                new AuthorizationResource(
                        "trust-ledger", null, subjectId.toString(), Map.of("ownerAccountId", subjectId.toString())),
                "v2");
        assertDeny(
                service.decide(withClientSuppliedFact, AuthorizationActionDescriptorRegistry.TRUST_LEDGER_WORKLOAD),
                AuthorizationDenyReason.MALFORMED_REQUEST);
    }

    @Test
    void bindsDocumentVaultActionsToTheDocumentVaultWorkloadAndPersonalTenant() {
        activeMemberV2();
        UUID documentId = UUID.randomUUID();
        AuthorizationRequest ownDocument = new AuthorizationRequest(
                subjectId,
                sessionId,
                proofFor(sessionId),
                AuthorizationAction.DOCUMENT_UPDATE.value(),
                new AuthorizationResource(
                        "document",
                        documentId.toString(),
                        subjectId.toString(),
                        Map.of("ownerAccountId", subjectId.toString(), "resourceExists", "true")),
                "v2");
        AuthorizationRequest search = new AuthorizationRequest(
                subjectId,
                sessionId,
                proofFor(sessionId),
                AuthorizationAction.DOCUMENT_SEARCH.value(),
                new AuthorizationResource("document", null, subjectId.toString(), Map.of()),
                "v2");

        assertThat(service.decide(ownDocument, AuthorizationActionDescriptorRegistry.DOCUMENT_VAULT_WORKLOAD)
                        .outcome())
                .isEqualTo(DecisionOutcome.ALLOW);
        assertThat(service.decide(search, AuthorizationActionDescriptorRegistry.DOCUMENT_VAULT_WORKLOAD)
                        .outcome())
                .isEqualTo(DecisionOutcome.ALLOW);
        assertDeny(
                service.decide(ownDocument, AuthorizationActionDescriptorRegistry.MEDIA_WORKLOAD),
                AuthorizationDenyReason.WORKLOAD_NOT_AUTHORIZED);

        AuthorizationRequest anotherOwner = new AuthorizationRequest(
                subjectId,
                sessionId,
                proofFor(sessionId),
                AuthorizationAction.DOCUMENT_READ.value(),
                new AuthorizationResource(
                        "document",
                        documentId.toString(),
                        subjectId.toString(),
                        Map.of("ownerAccountId", UUID.randomUUID().toString(), "resourceExists", "true")),
                "v2");
        assertDeny(
                service.decide(anotherOwner, AuthorizationActionDescriptorRegistry.DOCUMENT_VAULT_WORKLOAD),
                AuthorizationDenyReason.OWNER_MISMATCH);
    }

    @Test
    void bindsMediaAssetsAndSessionsToTheMediaWorkloadAndPersonalTenant() {
        activeMemberV2();
        UUID assetId = UUID.randomUUID();
        AuthorizationRequest upload = new AuthorizationRequest(
                subjectId,
                sessionId,
                proofFor(sessionId),
                AuthorizationAction.MEDIA_ASSET_UPLOAD.value(),
                new AuthorizationResource(
                        "media-asset",
                        assetId.toString(),
                        subjectId.toString(),
                        Map.of("ownerAccountId", subjectId.toString(), "resourceExists", "true")),
                "v2");
        AuthorizationRequest createSession = new AuthorizationRequest(
                subjectId,
                sessionId,
                proofFor(sessionId),
                AuthorizationAction.MEDIA_SESSION_CREATE.value(),
                new AuthorizationResource(
                        "media-session",
                        UUID.randomUUID().toString(),
                        subjectId.toString(),
                        Map.of("ownerAccountId", subjectId.toString())),
                "v2");
        AuthorizationRequest list = new AuthorizationRequest(
                subjectId,
                sessionId,
                proofFor(sessionId),
                AuthorizationAction.MEDIA_SESSION_LIST.value(),
                new AuthorizationResource("media", null, subjectId.toString(), Map.of()),
                "v2");

        assertThat(service.decide(upload, AuthorizationActionDescriptorRegistry.MEDIA_WORKLOAD).outcome())
                .isEqualTo(DecisionOutcome.ALLOW);
        assertThat(service.decide(createSession, AuthorizationActionDescriptorRegistry.MEDIA_WORKLOAD)
                        .outcome())
                .isEqualTo(DecisionOutcome.ALLOW);
        assertThat(service.decide(list, AuthorizationActionDescriptorRegistry.MEDIA_WORKLOAD).outcome())
                .isEqualTo(DecisionOutcome.ALLOW);

        AuthorizationRequest crossTenantList = new AuthorizationRequest(
                subjectId,
                sessionId,
                proofFor(sessionId),
                AuthorizationAction.MEDIA_ASSET_LIST.value(),
                new AuthorizationResource("media", null, "another-tenant", Map.of()),
                "v2");
        assertDeny(
                service.decide(crossTenantList, AuthorizationActionDescriptorRegistry.MEDIA_WORKLOAD),
                AuthorizationDenyReason.TENANT_MISMATCH);
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

    private void activeMemberV2() {
        activeSubject(subjectId, sessionId, NOW.plusSeconds(300));
        when(policyRepository.loadCurrentPolicy()).thenReturn(v2Policy());
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
        return new DefaultAuthorizationPolicyRepository("v1").loadCurrentPolicy();
    }

    private AuthorizationPolicy v2Policy() {
        return new DefaultAuthorizationPolicyRepository("v2").loadCurrentPolicy();
    }

    private AuthorizationPolicy policyWithOnlyTenantAdmins() {
        return new AuthorizationPolicy("v1", Map.of(
                AuthorizationAction.GOAL_CREATE, EnumSet.of(AuthorizationRole.TENANT_ADMIN),
                AuthorizationAction.GOAL_LIST, EnumSet.of(AuthorizationRole.TENANT_ADMIN),
                AuthorizationAction.GOAL_READ, EnumSet.of(AuthorizationRole.TENANT_ADMIN),
                AuthorizationAction.GOAL_UPDATE, EnumSet.of(AuthorizationRole.TENANT_ADMIN),
                AuthorizationAction.GOAL_COMPLETE, EnumSet.of(AuthorizationRole.TENANT_ADMIN),
                AuthorizationAction.GOAL_ARCHIVE, EnumSet.of(AuthorizationRole.TENANT_ADMIN),
                AuthorizationAction.GOAL_DEPENDENCY_ORDER, EnumSet.of(AuthorizationRole.TENANT_ADMIN)));
    }

    private void assertDeny(AuthorizationDecision decision, AuthorizationDenyReason reason) {
        assertThat(decision.outcome()).isEqualTo(DecisionOutcome.DENY);
        assertThat(decision.reasonCode()).isEqualTo(reason.name());
    }
}

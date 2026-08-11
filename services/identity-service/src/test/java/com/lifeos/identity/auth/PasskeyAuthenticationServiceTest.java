package com.lifeos.identity.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifeos.identity.account.UserAccount;
import com.yubico.webauthn.AssertionRequest;
import com.yubico.webauthn.AssertionResult;
import com.yubico.webauthn.RelyingParty;
import com.yubico.webauthn.RegisteredCredential;
import com.yubico.webauthn.data.ByteArray;
import com.yubico.webauthn.data.ClientAssertionExtensionOutputs;
import com.yubico.webauthn.data.PublicKeyCredential;
import com.yubico.webauthn.data.AuthenticatorAssertionResponse;
import com.yubico.webauthn.data.UserVerificationRequirement;
import com.yubico.webauthn.exception.AssertionFailedException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Verifies passkey protocol orchestration, replay handling, counter compare-and-set behavior, and
 * convergence on the shared PASSKEY session authority.
 */
@ExtendWith(MockitoExtension.class)
class PasskeyAuthenticationServiceTest {

    private static final WebAuthnChallengeId CHALLENGE_ID =
            new WebAuthnChallengeId("c".repeat(43));
    private static final String CLIENT_ADDRESS = "127.0.0.1";

    @Mock
    private RelyingParty relyingParty;
    @Mock
    private WebAuthnChallengeStore challengeStore;
    @Mock
    private WebAuthnCredentialRepository credentialRepository;
    @Mock
    private LoginRateLimiter rateLimiter;
    @Mock
    private SessionTokenAuthority sessionTokenAuthority;
    @Mock
    private SecurityAuditService auditService;
    @Mock
    private LoginMetrics metrics;
    @Mock
    private WebAuthnAssertionParser assertionParser;
    @Mock
    private AssertionRequest assertionRequest;
    @Mock
    private PublicKeyCredential<AuthenticatorAssertionResponse, ClientAssertionExtensionOutputs> assertion;
    @Mock
    private AssertionResult assertionResult;
    @Mock
    private RegisteredCredential registeredCredential;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private IdentityAuthProperties properties;
    private PasskeyAuthenticationService service;

    @BeforeEach
    void setUp() {
        properties = new IdentityAuthProperties();
        service = new PasskeyAuthenticationService(
                properties,
                relyingParty,
                challengeStore,
                credentialRepository,
                rateLimiter,
                sessionTokenAuthority,
                auditService,
                metrics,
                assertionParser,
                objectMapper,
                Clock.fixed(Instant.parse("2026-08-10T00:00:00Z"), ZoneOffset.UTC),
                new java.security.SecureRandom());
    }

    @Test
    void successfulAssertionAdvancesCounterCreatesPasskeySessionAndAuditsSuccess() throws Exception {
        UserAccount account = account();
        WebAuthnCredential credential = credential(account, 4);
        ByteArray credentialId = ByteArray.fromBase64Url(credential.getCredentialId());
        LoginResponse response = new LoginResponse(UUID.randomUUID(), "signed-token", "Bearer", 300);
        when(challengeStore.consume(CHALLENGE_ID)).thenReturn(Optional.of(assertionRequest));
        when(assertionParser.parse(anyString())).thenReturn(assertion);
        when(relyingParty.finishAssertion(any())).thenReturn(assertionResult);
        when(assertionResult.isSuccess()).thenReturn(true);
        when(assertionResult.isUserVerified()).thenReturn(true);
        when(assertionResult.getCredential()).thenReturn(registeredCredential);
        when(registeredCredential.getCredentialId()).thenReturn(credentialId);
        when(assertionResult.getSignatureCount()).thenReturn(5L);
        when(credentialRepository.findByCredentialIdAndEnabledTrue(credential.getCredentialId()))
                .thenReturn(Optional.of(credential));
        when(credentialRepository.advanceSignatureCountIfCurrent(
                eq(credential.getId()), eq(4L), eq(5L), any())).thenReturn(1);
        when(sessionTokenAuthority.createSession(account, SessionAuthenticationMethod.PASSKEY))
                .thenReturn(response);

        LoginResponse actual = service.complete(request(), CLIENT_ADDRESS);

        assertThat(actual).isEqualTo(response);
        verify(sessionTokenAuthority).createSession(account, SessionAuthenticationMethod.PASSKEY);
        verify(auditService).recordWithinCurrentTransaction(
                SecurityAuditEventType.PASSKEY_LOGIN_SUCCEEDED, account.getId(), CLIENT_ADDRESS);
        verify(metrics).record(SecurityAuditEventType.PASSKEY_LOGIN_SUCCEEDED);
    }

    @Test
    void unverifiedAssertionIsRejectedWhenUserVerificationIsRequired() throws Exception {
        when(challengeStore.consume(CHALLENGE_ID)).thenReturn(Optional.of(assertionRequest));
        when(assertionParser.parse(anyString())).thenReturn(assertion);
        when(relyingParty.finishAssertion(any())).thenReturn(assertionResult);
        when(assertionResult.isSuccess()).thenReturn(true);
        when(assertionResult.isUserVerified()).thenReturn(false);

        assertThatThrownBy(() -> service.complete(request(), CLIENT_ADDRESS))
                .isInstanceOf(AuthenticationFailureException.class);

        verify(auditService).record(
                SecurityAuditEventType.PASSKEY_ASSERTION_REJECTED, null, CLIENT_ADDRESS);
        verifyNoSessionCreated();
    }

    @Test
    void unverifiedAssertionIsAcceptedWhenUserVerificationIsPreferred() throws Exception {
        properties.getWebauthn().setUserVerification(UserVerificationRequirement.PREFERRED);
        UserAccount account = account();
        WebAuthnCredential credential = credential(account, 4);
        ByteArray credentialId = ByteArray.fromBase64Url(credential.getCredentialId());
        LoginResponse response = new LoginResponse(UUID.randomUUID(), "signed-token", "Bearer", 300);
        when(challengeStore.consume(CHALLENGE_ID)).thenReturn(Optional.of(assertionRequest));
        when(assertionParser.parse(anyString())).thenReturn(assertion);
        when(relyingParty.finishAssertion(any())).thenReturn(assertionResult);
        when(assertionResult.isSuccess()).thenReturn(true);
        when(assertionResult.getCredential()).thenReturn(registeredCredential);
        when(registeredCredential.getCredentialId()).thenReturn(credentialId);
        when(assertionResult.getSignatureCount()).thenReturn(5L);
        when(credentialRepository.findByCredentialIdAndEnabledTrue(credential.getCredentialId()))
                .thenReturn(Optional.of(credential));
        when(credentialRepository.advanceSignatureCountIfCurrent(
                eq(credential.getId()), eq(4L), eq(5L), any())).thenReturn(1);
        when(sessionTokenAuthority.createSession(account, SessionAuthenticationMethod.PASSKEY))
                .thenReturn(response);

        assertThat(service.complete(request(), CLIENT_ADDRESS)).isEqualTo(response);
    }

    @Test
    void verifiedAssertionWithUnknownCredentialIsRejected() throws Exception {
        when(challengeStore.consume(CHALLENGE_ID)).thenReturn(Optional.of(assertionRequest));
        when(assertionParser.parse(anyString())).thenReturn(assertion);
        when(relyingParty.finishAssertion(any())).thenReturn(assertionResult);
        when(assertionResult.isSuccess()).thenReturn(true);
        when(assertionResult.isUserVerified()).thenReturn(true);
        when(assertionResult.getCredential()).thenReturn(registeredCredential);
        when(registeredCredential.getCredentialId())
                .thenReturn(ByteArray.fromBase64Url("Y3JlZGVudGlhbC1pZA"));
        when(credentialRepository.findByCredentialIdAndEnabledTrue("Y3JlZGVudGlhbC1pZA"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.complete(request(), CLIENT_ADDRESS))
                .isInstanceOf(AuthenticationFailureException.class);

        verify(auditService).record(
                SecurityAuditEventType.PASSKEY_ASSERTION_REJECTED, null, CLIENT_ADDRESS);
        verifyNoSessionCreated();
    }

    @Test
    void rateLimitedClientCannotStartOrCompleteCeremony() {
        doThrow(new LoginRateLimitExceededException(60))
                .when(rateLimiter).check("passkey", CLIENT_ADDRESS);

        assertThatThrownBy(() -> service.begin(CLIENT_ADDRESS))
                .isInstanceOf(LoginRateLimitExceededException.class);
        assertThatThrownBy(() -> service.complete(request(), CLIENT_ADDRESS))
                .isInstanceOf(LoginRateLimitExceededException.class);

        verify(auditService, org.mockito.Mockito.times(2)).record(
                SecurityAuditEventType.PASSKEY_LOGIN_RATE_LIMITED, null, CLIENT_ADDRESS);
        verifyNoSessionCreated();
    }

    @Test
    void staleOrReplayedChallengeIsRejectedBeforeAssertionParsingOrSessionCreation() throws Exception {
        when(challengeStore.consume(CHALLENGE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.complete(request(), CLIENT_ADDRESS))
                .isInstanceOf(AuthenticationFailureException.class);

        verify(auditService).record(
                SecurityAuditEventType.PASSKEY_ASSERTION_REJECTED, null, CLIENT_ADDRESS);
        verify(assertionParser, never()).parse(anyString());
        verifyNoSessionCreated();
    }

    @Test
    void invalidOriginOrSignatureFromProtocolValidatorIsRejectedAndAudited() throws Exception {
        when(challengeStore.consume(CHALLENGE_ID)).thenReturn(Optional.of(assertionRequest));
        when(assertionParser.parse(anyString())).thenReturn(assertion);
        when(relyingParty.finishAssertion(any())).thenThrow(new AssertionFailedException("invalid assertion"));

        assertThatThrownBy(() -> service.complete(request(), CLIENT_ADDRESS))
                .isInstanceOf(AuthenticationFailureException.class);

        verify(auditService).record(
                SecurityAuditEventType.PASSKEY_ASSERTION_REJECTED, null, CLIENT_ADDRESS);
        verifyNoSessionCreated();
    }

    @Test
    void concurrentCounterUpdateConflictCannotCreateSession() throws Exception {
        UserAccount account = account();
        WebAuthnCredential credential = credential(account, 4);
        ByteArray credentialId = ByteArray.fromBase64Url(credential.getCredentialId());
        when(challengeStore.consume(CHALLENGE_ID)).thenReturn(Optional.of(assertionRequest));
        when(assertionParser.parse(anyString())).thenReturn(assertion);
        when(relyingParty.finishAssertion(any())).thenReturn(assertionResult);
        when(assertionResult.isSuccess()).thenReturn(true);
        when(assertionResult.isUserVerified()).thenReturn(true);
        when(assertionResult.getCredential()).thenReturn(registeredCredential);
        when(registeredCredential.getCredentialId()).thenReturn(credentialId);
        when(assertionResult.getSignatureCount()).thenReturn(5L);
        when(credentialRepository.findByCredentialIdAndEnabledTrue(credential.getCredentialId()))
                .thenReturn(Optional.of(credential));
        when(credentialRepository.advanceSignatureCountIfCurrent(
                eq(credential.getId()), eq(4L), eq(5L), any())).thenReturn(0);

        assertThatThrownBy(() -> service.complete(request(), CLIENT_ADDRESS))
                .isInstanceOf(AuthenticationFailureException.class);

        verify(auditService).record(
                SecurityAuditEventType.PASSKEY_ASSERTION_REJECTED, null, CLIENT_ADDRESS);
        verifyNoSessionCreated();
    }

    @Test
    void signatureCounterRegressionCannotCreateSession() throws Exception {
        UserAccount account = account();
        WebAuthnCredential credential = credential(account, 4);
        ByteArray credentialId = ByteArray.fromBase64Url(credential.getCredentialId());
        when(challengeStore.consume(CHALLENGE_ID)).thenReturn(Optional.of(assertionRequest));
        when(assertionParser.parse(anyString())).thenReturn(assertion);
        when(relyingParty.finishAssertion(any())).thenReturn(assertionResult);
        when(assertionResult.isSuccess()).thenReturn(true);
        when(assertionResult.isUserVerified()).thenReturn(true);
        when(assertionResult.getCredential()).thenReturn(registeredCredential);
        when(registeredCredential.getCredentialId()).thenReturn(credentialId);
        when(assertionResult.getSignatureCount()).thenReturn(3L);
        when(credentialRepository.findByCredentialIdAndEnabledTrue(credential.getCredentialId()))
                .thenReturn(Optional.of(credential));

        assertThatThrownBy(() -> service.complete(request(), CLIENT_ADDRESS))
                .isInstanceOf(AuthenticationFailureException.class);

        verify(auditService).record(
                SecurityAuditEventType.PASSKEY_ASSERTION_REJECTED, null, CLIENT_ADDRESS);
        verify(credentialRepository, never()).advanceSignatureCountIfCurrent(
                any(), anyLong(), anyLong(), any());
        verifyNoSessionCreated();
    }

    @Test
    void beginStoresExactServerRequestAndReturnsBrowserPublicKeyOptions() throws Exception {
        when(relyingParty.startAssertion(any())).thenReturn(assertionRequest);
        when(assertionRequest.toCredentialsGetJson())
                .thenReturn("{\"publicKey\":{\"challenge\":\"challenge\",\"rpId\":\"localhost\"}}");

        PasskeyAuthenticationOptions options = service.begin(CLIENT_ADDRESS);

        assertThat(options.challengeId()).matches("[A-Za-z0-9_-]{43}");
        assertThat(options.publicKey().get("rpId").asText()).isEqualTo("localhost");
        verify(challengeStore).save(
                eq(new WebAuthnChallengeId(options.challengeId())),
                eq(assertionRequest), eq(java.time.Duration.ofMinutes(5)));
    }

    private PasskeyAuthenticationRequest request() throws Exception {
        return new PasskeyAuthenticationRequest(
                CHALLENGE_ID.value(),
                objectMapper.readTree("{\"id\":\"credential\",\"response\":{}}"));
    }

    private UserAccount account() {
        UserAccount account = new UserAccount("ada@example.com", "Ada Lovelace");
        ReflectionTestUtils.setField(account, "id", UUID.randomUUID());
        return account;
    }

    private WebAuthnCredential credential(UserAccount account, long signatureCount) throws Exception {
        WebAuthnCredential credential = new WebAuthnCredential(
                account,
                ByteArray.fromBase64Url("Y3JlZGVudGlhbC1pZA"),
                ByteArray.fromBase64Url("dXNlci1oYW5kbGU"),
                new ByteArray(new byte[] {1, 2, 3}),
                signatureCount);
        ReflectionTestUtils.setField(credential, "id", UUID.randomUUID());
        return credential;
    }

    private void verifyNoSessionCreated() {
        verify(sessionTokenAuthority, never()).createSession(any(), eq(SessionAuthenticationMethod.PASSKEY));
    }
}

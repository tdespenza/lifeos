package com.lifeos.identity.auth;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.stereotype.Component;

/**
 * Authenticates callers of internal identity-service HTTP adapters.
 *
 * <p>This is a transitional REST adapter while the repository has no generated gRPC/mTLS
 * contract module. The verifier never treats a caller-supplied identity header as proof: it
 * requires a deployment-managed credential and compares it in constant time. Infrastructure must
 * additionally restrict this endpoint to trusted service traffic and enforce TLS/mTLS where its
 * platform supports it.
 */
@Component
public class InternalWorkloadIdentityVerifier {

    /** Header carrying the bounded service identity. */
    public static final String IDENTITY_HEADER = "X-LifeOS-Workload-Identity";

    /** Header carrying the deployment-managed workload credential. */
    public static final String TOKEN_HEADER = "X-LifeOS-Workload-Token";

    private static final int MAX_IDENTITY_LENGTH = 128;
    private final IdentityAuthProperties.Authorization properties;

    /**
     * Creates the verifier from externally configured workload credentials.
     *
     * @param properties identity-service authorization configuration
     */
    public InternalWorkloadIdentityVerifier(IdentityAuthProperties properties) {
        this.properties = properties.getAuthorization();
    }

    /**
     * Verifies the caller's workload identity and credential.
     *
     * @param request current internal HTTP request
     * @return verified workload identity for bounded rate accounting
     * @throws InternalWorkloadAuthenticationException when either header is absent, malformed,
     *         unknown, or mismatched
     */
    public String verify(HttpServletRequest request) {
        String workloadIdentity = request.getHeader(IDENTITY_HEADER);
        String suppliedCredential = request.getHeader(TOKEN_HEADER);
        if (!isSafeIdentity(workloadIdentity) || suppliedCredential == null || suppliedCredential.isBlank()) {
            throw new InternalWorkloadAuthenticationException();
        }
        String expectedCredential = properties.workloadCredential(workloadIdentity);
        if (expectedCredential == null || expectedCredential.isBlank()
                || !MessageDigest.isEqual(
                        expectedCredential.getBytes(StandardCharsets.UTF_8),
                        suppliedCredential.getBytes(StandardCharsets.UTF_8))) {
            throw new InternalWorkloadAuthenticationException();
        }
        return workloadIdentity;
    }

    private boolean isSafeIdentity(String value) {
        return value != null && !value.isBlank() && value.length() <= MAX_IDENTITY_LENGTH;
    }
}

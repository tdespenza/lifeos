package com.lifeos.identity.auth;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Versioned authenticated REST boundary for device and session management. */
@RestController
public class SessionController {

    private final SessionManagementService sessionService;
    private final JwtValidationService validationService;
    private final ClientAddressResolver clientAddressResolver;
    private final IdentityAuthProperties properties;

    /**
     * Creates the session-management boundary.
     *
     * @param sessionService session ownership and mutation service
     * @param validationService bearer/JWT plus durable session validator
     * @param clientAddressResolver trusted address resolver for audit fingerprints
     * @param properties bounded page settings
     */
    @Autowired
    public SessionController(
            SessionManagementService sessionService,
            JwtValidationService validationService,
            ClientAddressResolver clientAddressResolver,
            IdentityAuthProperties properties) {
        this.sessionService = sessionService;
        this.validationService = validationService;
        this.clientAddressResolver = clientAddressResolver;
        this.properties = properties;
    }

    /**
     * Lists the authenticated account's unexpired session rows.
     *
     * @param cursor opaque cursor from the previous page
     * @param limit bounded requested page size
     * @param request bearer request
     * @return safe session page
     */
    @GetMapping("/api/v1/auth/sessions")
    public ResponseEntity<SessionPage> list(
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit,
            HttpServletRequest request) {
        AuthenticatedSubject subject = authenticate(request);
        int pageSize = limit == null ? properties.getDefaultSessionPageSize() : limit;
        validatePageSize(pageSize);
        SessionPage page = sessionService.listOwnedSessions(
                subject, cursor == null || cursor.isBlank() ? null : cursor, pageSize);
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(page);
    }

    /**
     * Revokes one owned session. Repeating the request is safe and returns 204 again.
     *
     * @param sessionId target session identifier
     * @param request bearer request
     * @return no-content response
     */
    @PostMapping("/api/v1/auth/sessions/{sessionId}/revoke")
    public ResponseEntity<Void> revoke(
            @PathVariable UUID sessionId, HttpServletRequest request) {
        AuthenticatedSubject subject = authenticate(request);
        sessionService.revokeOwnedSession(
                subject, sessionId, clientAddressResolver.resolve(request));
        return ResponseEntity.noContent().header(HttpHeaders.CACHE_CONTROL, "no-store").build();
    }

    /**
     * Revokes every active session except the authenticated current session.
     *
     * @param request bearer request
     * @return no-content response
     */
    @PostMapping("/api/v1/auth/sessions/revoke-others")
    public ResponseEntity<Void> revokeOthers(HttpServletRequest request) {
        AuthenticatedSubject subject = authenticate(request);
        sessionService.revokeOtherSessions(subject, clientAddressResolver.resolve(request));
        return ResponseEntity.noContent().header(HttpHeaders.CACHE_CONTROL, "no-store").build();
    }

    private AuthenticatedSubject authenticate(HttpServletRequest request) {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            throw new AuthenticationFailureException();
        }
        String token = authorization.substring(7).trim();
        if (token.isBlank()) {
            throw new AuthenticationFailureException();
        }
        return validationService.validate(token);
    }

    private void validatePageSize(int pageSize) {
        if (pageSize < 1 || pageSize > properties.getMaxSessionPageSize()) {
            throw new SessionRequestValidationException();
        }
    }
}

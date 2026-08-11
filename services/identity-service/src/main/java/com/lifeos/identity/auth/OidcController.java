package com.lifeos.identity.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Versioned REST boundaries for the OIDC authorization-code flow.
 */
@RestController
@RequestMapping("/api/v1/auth/oidc")
public class OidcController {

    private static final String OIDC_FAILURE = "The OIDC authentication request could not be completed.";
    private static final String TEMPORARY_FAILURE = "Authentication is temporarily unavailable.";
    private static final String CAPACITY_FAILURE = "The account cannot create another active session.";

    private final OidcAuthenticationService authenticationService;
    private final ClientAddressResolver clientAddressResolver;

    /**
     * Creates the OIDC controller.
     *
     * @param authenticationService OIDC flow service
     */
    public OidcController(OidcAuthenticationService authenticationService) {
        this(authenticationService, new ClientAddressResolver(new IdentityAuthProperties()));
    }

    /**
     * Creates the OIDC controller with trusted-proxy-aware client address resolution.
     *
     * @param authenticationService OIDC flow service
     * @param clientAddressResolver client address resolver
     */
    @org.springframework.beans.factory.annotation.Autowired
    public OidcController(
            OidcAuthenticationService authenticationService,
            ClientAddressResolver clientAddressResolver) {
        this.authenticationService = authenticationService;
        this.clientAddressResolver = clientAddressResolver;
    }

    /**
     * Starts an allow-listed provider authorization redirect.
     *
     * @param provider provider name
     * @param codeChallenge client-generated PKCE challenge
     * @param codeChallengeMethod PKCE method
     * @return 302 redirect to the provider
     */
    @GetMapping("/{provider}/authorize")
    public ResponseEntity<Void> authorize(
            @PathVariable String provider,
            @RequestParam(name = "code_challenge") String codeChallenge,
            @RequestParam(name = "code_challenge_method") String codeChallengeMethod) {
        URI location = authenticationService.begin(
                provider, new OidcAuthorizationRequest(codeChallenge, codeChallengeMethod));
        return ResponseEntity.status(HttpStatus.FOUND).location(location).build();
    }

    /**
     * Starts a browser-safe authorization redirect from a form POST. The verifier is stored in
     * the short-lived callback state, so the provider can redirect directly to the callback.
     *
     * @param provider provider name
     * @param codeChallenge client-generated PKCE challenge
     * @param codeChallengeMethod PKCE method
     * @param codeVerifier client-generated PKCE verifier
     * @return 302 redirect to the provider
     */
    @PostMapping(value = "/{provider}/authorize", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<Void> authorizeForm(
            @PathVariable String provider,
            @RequestParam(name = "code_challenge") String codeChallenge,
            @RequestParam(name = "code_challenge_method") String codeChallengeMethod,
            @RequestParam(name = "code_verifier") String codeVerifier) {
        return redirect(provider, new OidcAuthorizationStartRequest(
                codeChallenge, codeChallengeMethod, codeVerifier));
    }

    /**
     * Starts a browser-safe authorization redirect from a JSON request.
     *
     * @param provider provider name
     * @param request client-generated PKCE challenge and verifier
     * @return 302 redirect to the provider
     */
    @PostMapping(value = "/{provider}/authorize", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> authorizeJson(
            @PathVariable String provider,
            @Valid @RequestBody OidcAuthorizationStartRequest request) {
        return redirect(provider, request);
    }

    /**
     * Completes the callback. Browser-safe authorization starts retain the verifier in server-side
     * callback state. The legacy GET flow may supply it as a private-client header; query-form
     * verifiers are deliberately not accepted because query strings are commonly logged.
     *
     * @param provider provider name
     * @param code provider authorization code
     * @param state callback state
     * @param headerCodeVerifier optional header-form PKCE verifier
     * @param error provider error value
     * @param servletRequest request used only for keyed audit fingerprinting
     * @return shared session/token response
     */
    @GetMapping(value = "/{provider}/callback", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<LoginResponse> callback(
            @PathVariable String provider,
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String state,
            @RequestHeader(name = "X-PKCE-Code-Verifier", required = false) String headerCodeVerifier,
            @RequestParam(required = false) String error,
            HttpServletRequest servletRequest) {
        return ResponseEntity.ok(authenticationService.callback(
                provider, code, state, headerCodeVerifier, error,
                clientAddressResolver.resolve(servletRequest)));
    }

    @ExceptionHandler(OidcAuthenticationFailureException.class)
    public ResponseEntity<ProblemDetail> handleOidcFailure() {
        return problem(HttpStatus.UNAUTHORIZED, OIDC_FAILURE);
    }

    @ExceptionHandler(AuthenticationFailureException.class)
    public ResponseEntity<ProblemDetail> handleAuthenticationFailure() {
        return problem(HttpStatus.UNAUTHORIZED, OIDC_FAILURE);
    }

    @ExceptionHandler(AuthenticationDependencyUnavailableException.class)
    public ResponseEntity<ProblemDetail> handleDependencyFailure() {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, TEMPORARY_FAILURE);
    }

    @ExceptionHandler(SessionCapacityExceededException.class)
    public ResponseEntity<ProblemDetail> handleSessionCapacity() {
        return problem(HttpStatus.CONFLICT, CAPACITY_FAILURE);
    }

    @ExceptionHandler({
        MethodArgumentNotValidException.class,
        MissingServletRequestParameterException.class
    })
    public ResponseEntity<ProblemDetail> handleInvalidRequest() {
        return problem(HttpStatus.BAD_REQUEST, OIDC_FAILURE);
    }

    private ResponseEntity<ProblemDetail> problem(HttpStatus status, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        return ResponseEntity.status(status).body(problem);
    }

    private ResponseEntity<Void> redirect(String provider, OidcAuthorizationStartRequest request) {
        URI location = authenticationService.begin(provider, request.challengeRequest(), request.codeVerifier());
        return ResponseEntity.status(HttpStatus.FOUND).location(location).build();
    }
}

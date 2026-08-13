package com.lifeos.identity.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import jakarta.servlet.FilterChain;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** Verifies workload authentication and body limits run before MVC JSON deserialization. */
class InternalAuthorizationRequestFilterTest {

    @Test
    void rejectsOversizedBodyBeforeWorkloadVerificationOrDownstreamBinding() throws Exception {
        InternalWorkloadIdentityVerifier verifier = org.mockito.Mockito.mock(InternalWorkloadIdentityVerifier.class);
        InternalWorkloadRateLimiter limiter = org.mockito.Mockito.mock(InternalWorkloadRateLimiter.class);
        FilterChain chain = org.mockito.Mockito.mock(FilterChain.class);
        InternalAuthorizationRequestFilter filter = new InternalAuthorizationRequestFilter(verifier, limiter);
        MockHttpServletRequest request = decisionRequest(new byte[
                InternalAuthorizationRequestFilter.MAX_DECISION_REQUEST_BYTES + 1]);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString()).contains("Internal authorization request is too large.");
        verifyNoInteractions(verifier, limiter, chain);
    }

    @Test
    void enforcesTheBodyLimitWhenChunkedTrafficHasNoContentLength() throws Exception {
        InternalWorkloadIdentityVerifier verifier = org.mockito.Mockito.mock(InternalWorkloadIdentityVerifier.class);
        InternalWorkloadRateLimiter limiter = org.mockito.Mockito.mock(InternalWorkloadRateLimiter.class);
        FilterChain chain = org.mockito.Mockito.mock(FilterChain.class);
        when(verifier.verify(any())).thenReturn("task-goal-service");
        InternalAuthorizationRequestFilter filter = new InternalAuthorizationRequestFilter(verifier, limiter);
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", InternalAuthorizationRequestFilter.DECISION_PATH) {
            @Override
            public long getContentLengthLong() {
                return -1;
            }
        };
        request.setContent(new byte[InternalAuthorizationRequestFilter.MAX_DECISION_REQUEST_BYTES + 1]);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(413);
        verifyNoInteractions(chain);
    }

    @Test
    void rejectsUnauthenticatedWorkloadBeforeReadingTheDecisionBodyOrCallingDownstream() throws Exception {
        InternalWorkloadIdentityVerifier verifier = org.mockito.Mockito.mock(InternalWorkloadIdentityVerifier.class);
        InternalWorkloadRateLimiter limiter = org.mockito.Mockito.mock(InternalWorkloadRateLimiter.class);
        FilterChain chain = org.mockito.Mockito.mock(FilterChain.class);
        doThrow(new InternalWorkloadAuthenticationException()).when(verifier).verify(any());
        InternalAuthorizationRequestFilter filter = new InternalAuthorizationRequestFilter(verifier, limiter);
        MockHttpServletRequest request = decisionRequest("{\"attributes\":{}}".getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("Internal authorization request failed.");
        verifyNoInteractions(limiter, chain);
    }

    @Test
    void passesOnlyAReplayableBoundedBodyAfterWorkloadAuthentication() throws Exception {
        InternalWorkloadIdentityVerifier verifier = org.mockito.Mockito.mock(InternalWorkloadIdentityVerifier.class);
        InternalWorkloadRateLimiter limiter = org.mockito.Mockito.mock(InternalWorkloadRateLimiter.class);
        FilterChain chain = (request, response) -> {
            var httpRequest = (jakarta.servlet.http.HttpServletRequest) request;
            assertThat(InternalAuthorizationRequestFilter.verifiedWorkloadIdentity(httpRequest))
                    .isEqualTo("task-goal-service");
            assertThat(new String(httpRequest.getInputStream().readAllBytes(), StandardCharsets.UTF_8))
                    .isEqualTo("{\"action\":\"goal:read\"}");
        };
        when(verifier.verify(any())).thenReturn("task-goal-service");
        InternalAuthorizationRequestFilter filter = new InternalAuthorizationRequestFilter(verifier, limiter);
        MockHttpServletRequest request = decisionRequest("{\"action\":\"goal:read\"}".getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
    }

    private MockHttpServletRequest decisionRequest(byte[] body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", InternalAuthorizationRequestFilter.DECISION_PATH);
        request.setContentType("application/json");
        request.setContent(body);
        return request;
    }
}

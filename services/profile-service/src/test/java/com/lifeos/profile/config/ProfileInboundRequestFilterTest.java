package com.lifeos.profile.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** Covers both declared and chunked-body limits before JSON deserialization is allowed to grow. */
class ProfileInboundRequestFilterTest {

    @Test
    void rejectsAnOversizedChunkedBodyEvenWithoutADeclaredContentLength() throws Exception {
        ProfileInboundRequestFilter filter = new ProfileInboundRequestFilter(propertiesWithMaximumBodyBytes(4));
        MockHttpServletRequest request = unknownLengthRequest("12345");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) ->
                ignoredRequest.getInputStream().readAllBytes());

        assertThat(response.getStatus()).isEqualTo(413);
    }

    @Test
    void acceptsAnExactLimitChunkedBody() throws Exception {
        ProfileInboundRequestFilter filter = new ProfileInboundRequestFilter(propertiesWithMaximumBodyBytes(4));
        MockHttpServletRequest request = unknownLengthRequest("1234");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<byte[]> received = new AtomicReference<>();

        filter.doFilter(request, response, (boundedRequest, ignoredResponse) ->
                received.set(boundedRequest.getInputStream().readAllBytes()));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(received.get()).isEqualTo("1234".getBytes(StandardCharsets.UTF_8));
    }

    private static ProfileServiceProperties propertiesWithMaximumBodyBytes(long maximumBodyBytes) {
        ProfileServiceProperties properties = new ProfileServiceProperties();
        properties.setMaxInboundBodyBytes(maximumBodyBytes);
        properties.setMaxConcurrentRequests(1);
        return properties;
    }

    private static MockHttpServletRequest unknownLengthRequest(String body) {
        MockHttpServletRequest request = new MockHttpServletRequest() {
            @Override
            public long getContentLengthLong() {
                return -1;
            }
        };
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }
}

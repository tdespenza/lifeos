package com.lifeos.assistant.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifeos.assistant.observability.CorrelationIdFilter;
import com.lifeos.assistant.observability.CorrelationIdSupport;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class AssistantInboundRequestFilterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void correlationContextIsOrderedBeforeInboundRejections() {
        assertThat(orderOf(CorrelationIdFilter.class)).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
        assertThat(orderOf(AssistantInboundRequestFilter.class))
                .isEqualTo(Ordered.HIGHEST_PRECEDENCE + 1);
    }

    @Test
    void saturatedInboundBulkheadUsesTheStableErrorEnvelopeAndRetriesAfterOneSecond() throws Exception {
        AssistantInboundRequestFilter inboundFilter = new AssistantInboundRequestFilter(properties(), objectMapper);
        CorrelationIdFilter correlationFilter = new CorrelationIdFilter();
        String firstCorrelationId = UUID.randomUUID().toString();
        String secondCorrelationId = UUID.randomUUID().toString();
        MockHttpServletRequest firstRequest = request(firstCorrelationId);
        MockHttpServletResponse firstResponse = new MockHttpServletResponse();
        MockHttpServletRequest secondRequest = request(secondCorrelationId);
        MockHttpServletResponse secondResponse = new MockHttpServletResponse();

        correlationFilter.doFilter(firstRequest, firstResponse, (correlatedFirstRequest, correlatedFirstResponse) -> {
            inboundFilter.doFilter(
                    (HttpServletRequest) correlatedFirstRequest,
                    (HttpServletResponse) correlatedFirstResponse,
                    (ignoredFirstRequest, ignoredFirstResponse) -> correlationFilter.doFilter(
                            secondRequest,
                            secondResponse,
                            (correlatedSecondRequest, correlatedSecondResponse) -> inboundFilter.doFilter(
                                    (HttpServletRequest) correlatedSecondRequest,
                                    (HttpServletResponse) correlatedSecondResponse,
                                    (ignoredSecondRequest, ignoredSecondResponse) -> {
                                        throw new AssertionError("saturated request must not reach the application");
                                    })));
        });

        assertThat(secondResponse.getStatus()).isEqualTo(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        assertThat(secondResponse.getContentType()).startsWith("application/json");
        assertThat(secondResponse.getHeader(CorrelationIdSupport.HEADER_NAME)).isEqualTo(secondCorrelationId);
        assertThat(secondResponse.getHeader(HttpHeaders.RETRY_AFTER)).isEqualTo("1");

        JsonNode body = objectMapper.readTree(secondResponse.getContentAsByteArray());
        assertThat(body.path("code").asText()).isEqualTo("INBOUND_REQUEST_CAPACITY_EXCEEDED");
        assertThat(body.path("message").asText()).isEqualTo("Assistant request capacity is temporarily unavailable");
        assertThat(body.path("retryable").asBoolean()).isTrue();
        assertThat(body.path("correlationId").asText()).isEqualTo(secondCorrelationId);
    }

    @Test
    void chunkedOversizedBodiesUseTheStableErrorEnvelopeWithCorrelation() throws Exception {
        AssistantInboundRequestFilter inboundFilter = new AssistantInboundRequestFilter(properties(4), objectMapper);
        CorrelationIdFilter correlationFilter = new CorrelationIdFilter();
        String correlationId = UUID.randomUUID().toString();
        MockHttpServletRequest request = chunkedRequest(correlationId, new byte[5]);
        MockHttpServletResponse response = new MockHttpServletResponse();

        correlationFilter.doFilter(request, response, (correlatedRequest, correlatedResponse) -> inboundFilter.doFilter(
                (HttpServletRequest) correlatedRequest,
                (HttpServletResponse) correlatedResponse,
                (wrappedRequest, ignoredResponse) -> {
                    ((HttpServletRequest) wrappedRequest).getInputStream().readAllBytes();
                }));

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(response.getHeader(CorrelationIdSupport.HEADER_NAME)).isEqualTo(correlationId);
        assertThat(response.getHeader(HttpHeaders.RETRY_AFTER)).isNull();

        JsonNode body = objectMapper.readTree(response.getContentAsByteArray());
        assertThat(body.path("code").asText()).isEqualTo("PAYLOAD_TOO_LARGE");
        assertThat(body.path("message").asText()).isEqualTo("Request payload too large");
        assertThat(body.path("retryable").asBoolean()).isFalse();
        assertThat(body.path("correlationId").asText()).isEqualTo(correlationId);
    }

    private static int orderOf(Class<?> filterType) {
        Order order = filterType.getAnnotation(Order.class);
        assertThat(order).isNotNull();
        return order.value();
    }

    private static AiAssistantProperties properties() {
        return properties(16_384);
    }

    private static AiAssistantProperties properties(long maxInboundBodyBytes) {
        AiAssistantProperties properties = new AiAssistantProperties();
        properties.setMaxInboundBodyBytes(maxInboundBodyBytes);
        properties.setMaxConcurrentRequests(1);
        return properties;
    }

    private static MockHttpServletRequest request(String correlationId) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/assistant/conversations");
        request.addHeader(CorrelationIdSupport.HEADER_NAME, correlationId);
        return request;
    }

    private static MockHttpServletRequest chunkedRequest(String correlationId, byte[] content) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/assistant/conversations") {
            @Override
            public long getContentLengthLong() {
                return -1;
            }
        };
        request.addHeader(CorrelationIdSupport.HEADER_NAME, correlationId);
        request.setContent(content);
        return request;
    }
}

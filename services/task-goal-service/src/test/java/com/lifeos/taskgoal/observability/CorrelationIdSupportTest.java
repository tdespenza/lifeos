package com.lifeos.taskgoal.observability;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class CorrelationIdSupportTest {

    @Test
    void acceptsUuidV7CorrelationIdsFromTheGateway() {
        String correlationId = "11111111-1111-7111-8111-111111111111";
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdSupport.HEADER_NAME, correlationId);

        assertThat(CorrelationIdSupport.resolve(request)).isEqualTo(correlationId);
    }
}

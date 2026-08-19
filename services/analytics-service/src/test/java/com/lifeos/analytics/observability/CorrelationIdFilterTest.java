package com.lifeos.analytics.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/** Ensures analytics emits a sanitized correlation ID for every direct request. */
@SpringBootTest
@AutoConfigureMockMvc
class CorrelationIdFilterTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void preservesOneCanonicalCorrelationId() throws Exception {
        UUID correlationId = UUID.randomUUID();
        String returned = mockMvc.perform(get("/api/v1/analytics/dashboard")
                        .header(CorrelationIdSupport.HEADER_NAME, correlationId))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(CorrelationIdSupport.HEADER_NAME, correlationId.toString()))
                .andReturn()
                .getResponse()
                .getHeader(CorrelationIdSupport.HEADER_NAME);

        assertThat(returned).isEqualTo(correlationId.toString());
    }

    @Test
    void replacesMalformedOrDuplicateCorrelationHeaders() throws Exception {
        String returned = mockMvc.perform(get("/api/v1/analytics/dashboard")
                        .header(CorrelationIdSupport.HEADER_NAME, "not-a-uuid", "still-not-a-uuid"))
                .andExpect(status().isUnauthorized())
                .andReturn()
                .getResponse()
                .getHeader(CorrelationIdSupport.HEADER_NAME);

        assertThat(CorrelationIdSupport.isValid(returned)).isTrue();
    }
}

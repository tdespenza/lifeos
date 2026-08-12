package com.lifeos.identity.auth;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Verifies that refresh responses do not permit caching of token material. */
@ExtendWith(MockitoExtension.class)
class RefreshControllerTest {

    @Mock
    private RefreshTokenService refreshTokenService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new RefreshController(
                refreshTokenService,
                new ClientAddressResolver(new IdentityAuthProperties()))).build();
    }

    @Test
    void successfulRefreshResponseIsNotCacheable() throws Exception {
        LoginResponse response = new LoginResponse(
                UUID.randomUUID(), "access-token", "Bearer", 300, "refresh-token", 600);
        when(refreshTokenService.refresh(any())).thenReturn(response);

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "retry-key")
                        .header("User-Agent", "test-agent")
                        .content("{\"refreshToken\":\"refresh-token\"}")
                        .with(request -> {
                            request.setRemoteAddr("192.0.2.10");
                            return request;
                        }))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Set-Cookie", containsString("lifeos_refresh=")));
    }
}

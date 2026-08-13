package com.lifeos.identity.auth;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class SessionControllerTest {

    @Mock
    private SessionManagementService sessionService;

    @Mock
    private JwtValidationService validationService;

    @Mock
    private ClientAddressResolver clientAddressResolver;

    private MockMvc mockMvc;
    private AuthenticatedSubject subject;

    @BeforeEach
    void setUp() {
        IdentityAuthProperties properties = new IdentityAuthProperties();
        properties.setDefaultSessionPageSize(20);
        properties.setMaxSessionPageSize(100);
        mockMvc = MockMvcBuilders.standaloneSetup(new SessionController(
                        sessionService, validationService, clientAddressResolver, properties))
                .setControllerAdvice(new AuthenticationExceptionHandler())
                .build();
        subject = new AuthenticatedSubject(UUID.randomUUID(), UUID.randomUUID(), "PASSWORD", "proof");
    }

    @Test
    void listsOnlyAfterBearerValidationAndUsesNoStore() throws Exception {
        when(validationService.validate("signed-token")).thenReturn(subject);
        when(sessionService.listOwnedSessions(eq(subject), eq(null), eq(20)))
                .thenReturn(new SessionPage(List.of(), null));

        mockMvc.perform(get("/api/v1/auth/sessions").header("Authorization", "Bearer signed-token"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.sessions").isArray())
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
    }

    @Test
    void rejectsMissingBearerWithoutCallingSessionService() throws Exception {
        mockMvc.perform(get("/api/v1/auth/sessions"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsUnboundedPageSizeWithoutCallingAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/auth/sessions")
                        .param("limit", "101")
                        .header("Authorization", "Bearer signed-token"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void revokesTargetAndReturnsNoContent() throws Exception {
        when(validationService.validate("signed-token")).thenReturn(subject);
        when(clientAddressResolver.resolve(any())).thenReturn("127.0.0.1");

        mockMvc.perform(post("/api/v1/auth/sessions/{id}/revoke", UUID.randomUUID())
                        .header("Authorization", "Bearer signed-token"))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Cache-Control", "no-store"));
    }
}

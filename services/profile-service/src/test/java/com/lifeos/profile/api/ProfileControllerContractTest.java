package com.lifeos.profile.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifeos.profile.audit.ProfileSecurityAuditEventRepository;
import com.lifeos.profile.authorization.ProfileAccessService;
import com.lifeos.profile.authorization.ProfileSubject;
import com.lifeos.profile.domain.HouseholdMemberRepository;
import com.lifeos.profile.domain.HouseholdRepository;
import com.lifeos.profile.domain.PersonalProfileRepository;
import com.lifeos.profile.domain.ProfileAiPersonalizationSettingsRepository;
import com.lifeos.profile.domain.ProfilePreferencesRepository;
import com.lifeos.profile.domain.ProfilePrivacySettingsRepository;
import com.lifeos.profile.idempotency.ProfileMutationIdempotencyRepository;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** Executable public HTTP contract for registration-like creation, conditional writes, and scope denial. */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:profile-contract;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.datasource.username=sa",
    "spring.datasource.password=profile-contract-password",
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.flyway.enabled=true",
    "spring.flyway.locations=classpath:db/migration-h2",
    "profile.idempotency-secret=contract-idempotency-secret",
    "profile.audit-client-fingerprint-secret=contract-audit-secret",
    "identity.workload-token=contract-workload-token"
})
@AutoConfigureMockMvc
class ProfileControllerContractTest {

    private static final String BEARER = "Bearer profile-contract-token";
    private static final String ACCESS_TOKEN_PROOF =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PersonalProfileRepository profileRepository;

    @Autowired
    private ProfilePreferencesRepository preferencesRepository;

    @Autowired
    private ProfilePrivacySettingsRepository privacyRepository;

    @Autowired
    private ProfileAiPersonalizationSettingsRepository aiSettingsRepository;

    @Autowired
    private HouseholdMemberRepository householdMemberRepository;

    @Autowired
    private HouseholdRepository householdRepository;

    @Autowired
    private ProfileMutationIdempotencyRepository idempotencyRepository;

    @Autowired
    private ProfileSecurityAuditEventRepository auditRepository;

    @MockitoBean
    private ProfileAccessService accessService;

    private ProfileSubject subject;

    @BeforeEach
    void setUp() {
        auditRepository.deleteAll();
        idempotencyRepository.deleteAll();
        householdMemberRepository.deleteAll();
        householdRepository.deleteAll();
        aiSettingsRepository.deleteAll();
        privacyRepository.deleteAll();
        preferencesRepository.deleteAll();
        profileRepository.deleteAll();
        reset(accessService);
        subject = subject();
        when(accessService.authenticate(anyString())).thenReturn(subject);
    }

    @Test
    void matchingCreateRetryReturnsTheExactOriginalSnapshotAfterALaterUpdate() throws Exception {
        MvcResult first = mockMvc.perform(post("/api/v1/profiles/me")
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .header("If-None-Match", "*")
                        .header("Idempotency-Key", "profile-create-replay-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(profileJson("Ada Lovelace")))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/profiles/me"))
                .andExpect(header().string("ETag", "\"0\""))
                .andExpect(header().string("Idempotent-Replayed", "false"))
                .andReturn();

        mockMvc.perform(put("/api/v1/profiles/me")
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .header("If-Match", "\"0\"")
                        .header("Idempotency-Key", "profile-update-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(profileJson("Ada Byron")))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.displayName").value("Ada Byron"));

        MvcResult replay = mockMvc.perform(post("/api/v1/profiles/me")
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .header("If-None-Match", "*")
                        .header("Idempotency-Key", "profile-create-replay-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(profileJson("Ada Lovelace")))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/profiles/me"))
                .andExpect(header().string("ETag", "\"0\""))
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andReturn();

        JsonNode firstBody = objectMapper.readTree(first.getResponse().getContentAsString());
        JsonNode replayBody = objectMapper.readTree(replay.getResponse().getContentAsString());
        assertThat(replayBody).isEqualTo(firstBody);
        assertThat(UUID.fromString(firstBody.path("id").asText())).isNotEqualTo(subject.accountId());
        assertThat(profileRepository.count()).isEqualTo(1L);
        assertThat(idempotencyRepository.count()).isEqualTo(2L);
    }

    @Test
    void distinctCreateKeyForAnExistingSelfProfileReturnsTheCreatePreconditionFailure() throws Exception {
        mockMvc.perform(post("/api/v1/profiles/me")
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .header("If-None-Match", "*")
                        .header("Idempotency-Key", "profile-create-first-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(profileJson("Ada Lovelace")))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/profiles/me")
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .header("If-None-Match", "*")
                        .header("Idempotency-Key", "profile-create-second-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(profileJson("Ada Lovelace")))
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.error").value("Profile representation is no longer current"));

        assertThat(idempotencyRepository.count()).isEqualTo(1L);
    }

    @Test
    void missingAndCrossHouseholdReadsReturnTheSameGenericNotFoundShape() throws Exception {
        UUID householdId = UUID.fromString(objectMapper.readTree(mockMvc.perform(post("/api/v1/households")
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .header("If-None-Match", "*")
                        .header("Idempotency-Key", "household-create-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Lovelace family\"}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString()).path("id").asText());

        when(accessService.authenticate(anyString())).thenReturn(otherSubject());
        MvcResult crossScope = mockMvc.perform(get("/api/v1/households/{householdId}", householdId)
                        .header(HttpHeaders.AUTHORIZATION, BEARER))
                .andExpect(status().isNotFound())
                .andReturn();
        MvcResult missing = mockMvc.perform(get("/api/v1/households/{householdId}", UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, BEARER))
                .andExpect(status().isNotFound())
                .andReturn();

        assertThat(crossScope.getResponse().getContentAsString())
                .isEqualTo(missing.getResponse().getContentAsString())
                .doesNotContain(householdId.toString());
    }

    @Test
    void requiresConditionalAndIdempotencyHeadersForAllWriteShapes() throws Exception {
        mockMvc.perform(post("/api/v1/profiles/me")
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .header("Idempotency-Key", "missing-create-condition")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(profileJson("Ada Lovelace")))
                .andExpect(status().is(428));

        mockMvc.perform(post("/api/v1/profiles/me")
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .header("If-None-Match", "*")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(profileJson("Ada Lovelace")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void journalStorageIsFailClosedWhenMongoIsNotExplicitlyEnabled() throws Exception {
        mockMvc.perform(post("/api/v1/profiles/me/journal")
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .header("Idempotency-Key", "journal-create-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Today\",\"content\":\"Private note\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("Journal storage is temporarily unavailable"));
    }

    private ProfileSubject subject() {
        return new ProfileSubject(UUID.randomUUID(), UUID.randomUUID(), "password", ACCESS_TOKEN_PROOF);
    }

    private ProfileSubject otherSubject() {
        return new ProfileSubject(UUID.randomUUID(), UUID.randomUUID(), "password", ACCESS_TOKEN_PROOF);
    }

    private static String profileJson(String displayName) {
        return "{\"displayName\":\"" + displayName
                + "\",\"locale\":\"en-US\",\"timeZone\":\"America/Chicago\",\"pronouns\":\"she/her\"}";
    }
}

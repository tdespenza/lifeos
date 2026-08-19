package com.lifeos.profile.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.reset;

import com.lifeos.profile.api.CreateProfileRequest;
import com.lifeos.profile.api.UpdateAiPersonalizationRequest;
import com.lifeos.profile.api.UpdatePreferencesRequest;
import com.lifeos.profile.api.UpdatePrivacySettingsRequest;
import com.lifeos.profile.authorization.ProfileAccessService;
import com.lifeos.profile.authorization.ProfileSubject;
import com.lifeos.profile.domain.AiContextCategory;
import com.lifeos.profile.domain.ProfileTheme;
import com.lifeos.profile.domain.ProfileVisibility;
import com.lifeos.profile.domain.WeekStart;
import com.lifeos.profile.idempotency.ProfileMutationIdempotencyRepository;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** H2 service/database integration coverage for the FR13–FR17 owned profile representations. */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:profile-service-integration;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.datasource.username=sa",
    "spring.datasource.password=profile-integration-password",
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.flyway.enabled=true",
    "spring.flyway.locations=classpath:db/migration-h2",
    "profile.idempotency-secret=integration-idempotency-secret",
    "profile.audit-client-fingerprint-secret=integration-audit-secret",
    "identity.workload-token=integration-workload-token"
})
class ProfileServiceIntegrationTest {

    private static final String ACCESS_TOKEN_PROOF =
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    @Autowired
    private ProfileManagementService service;

    @Autowired
    private ProfileMutationIdempotencyRepository idempotencyRepository;

    @MockitoBean
    private ProfileAccessService accessService;

    private ProfileSubject subject;

    @BeforeEach
    void setUp() {
        idempotencyRepository.deleteAll();
        reset(accessService);
        subject = new ProfileSubject(UUID.randomUUID(), UUID.randomUUID(), "password", ACCESS_TOKEN_PROOF);
    }

    @Test
    void createsAndConditionallyUpdatesValidatedPreferencesPrivacyAndAiConsent() {
        service.createProfile(
                subject,
                new CreateProfileRequest("Grace Hopper", "en-US", "America/New_York", null, "Compiler pioneer"),
                "integration-profile-create");

        ProfileMutationResult<?> preferences = service.updatePreferences(
                subject,
                0,
                new UpdatePreferencesRequest(ProfileTheme.DARK, WeekStart.SUNDAY, false, 14),
                "integration-preferences-update");
        ProfileMutationResult<?> privacy = service.updatePrivacySettings(
                subject,
                0,
                new UpdatePrivacySettingsRequest(ProfileVisibility.HOUSEHOLD, true, true),
                "integration-privacy-update");
        ProfileMutationResult<?> ai = service.updateAiPersonalizationSettings(
                subject,
                0,
                new UpdateAiPersonalizationRequest(
                        true,
                        true,
                        Set.of(AiContextCategory.PROFILE, AiContextCategory.GOALS, AiContextCategory.DOCUMENTS)),
                "integration-ai-update");

        assertThat(preferences.body().toString()).contains("DARK", "SUNDAY", "14");
        assertThat(privacy.body().toString()).contains("HOUSEHOLD");
        assertThat(ai.body().toString()).contains("PROFILE", "GOALS", "DOCUMENTS");
        long completedReservations = idempotencyRepository.count();
        assertThatThrownBy(() -> service.updatePreferences(
                        subject,
                        0,
                        new UpdatePreferencesRequest(ProfileTheme.LIGHT, WeekStart.MONDAY, true, 30),
                        "integration-preferences-stale"))
                .isInstanceOf(ProfileVersionConflictException.class);
        assertThat(idempotencyRepository.count()).isEqualTo(completedReservations);
    }

    @Test
    void rejectsAiDataCategoriesWithoutExplicitActiveConsent() {
        service.createProfile(
                subject,
                new CreateProfileRequest("Grace Hopper", "en-US", "America/New_York", null, null),
                "integration-ai-create");

        assertThatThrownBy(() -> service.updateAiPersonalizationSettings(
                        subject,
                        0,
                        new UpdateAiPersonalizationRequest(false, false, Set.of(AiContextCategory.PROFILE)),
                        "integration-ai-invalid"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

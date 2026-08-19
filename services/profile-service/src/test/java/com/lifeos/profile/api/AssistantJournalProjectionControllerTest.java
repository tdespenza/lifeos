package com.lifeos.profile.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lifeos.profile.authorization.ProfileSubject;
import com.lifeos.profile.config.ProfileAssistantProjectionProperties;
import com.lifeos.profile.domain.AiContextCategory;
import com.lifeos.profile.journal.JournalManagementService;
import com.lifeos.profile.service.ProfileManagementService;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AssistantJournalProjectionControllerTest {

    @Test
    void rejectsWithoutWorkloadCredential() {
        ProfileAssistantProjectionProperties properties = properties();
        AssistantJournalProjectionController controller = new AssistantJournalProjectionController(
                mock(ProfileManagementService.class), mock(JournalManagementService.class), properties);

        assertThatThrownBy(() -> controller.list(
                        "wrong",
                        "wrong",
                        request()))
                .isInstanceOf(AssistantJournalProjectionController.AssistantJournalWorkloadUnauthorizedException.class);
    }

    @Test
    void returnsOnlyConsentedBoundedJournalContent() {
        ProfileManagementService profileService = mock(ProfileManagementService.class);
        JournalManagementService journalService = mock(JournalManagementService.class);
        ProfileAssistantProjectionProperties properties = properties();
        ProfileSubject subject = subject();
        when(profileService.getAiPersonalizationSettings(subject)).thenReturn(new AiPersonalizationResponse(
                true, true, EnumSet.allOf(AiContextCategory.class), Instant.now(), 1, Instant.now()));
        when(journalService.list(subject, 2)).thenReturn(List.of(
                new JournalEntryResponse(
                        UUID.randomUUID(), "Title", "A bounded journal entry", Instant.now(), Instant.now(), 1)));

        AssistantJournalProjectionController.JournalProjectionResponse response = new AssistantJournalProjectionController(
                profileService, journalService, properties)
                .list("ai-assistant-service", "secret", request(subject))
                .getBody();

        assertThat(response.entries()).hasSize(1);
        assertThat(response.entries().getFirst().content()).isEqualTo("A bounded journal entry");
    }

    @Test
    void returnsPersonalizationConsentForAnalyticsWorkloadProjection() {
        ProfileManagementService profileService = mock(ProfileManagementService.class);
        ProfileSubject subject = subject();
        when(profileService.getAiPersonalizationSettings(subject)).thenReturn(new AiPersonalizationResponse(
                true, true, EnumSet.of(AiContextCategory.ANALYTICS), Instant.now(), 1, Instant.now()));

        AiPersonalizationResponse response = new AssistantJournalProjectionController(
                profileService, mock(JournalManagementService.class), properties())
                .personalization(
                        "ai-assistant-service",
                        "secret",
                        new AssistantJournalProjectionController.PersonalizationProjectionRequest(
                                subject.accountId(), subject.sessionId(), subject.authenticationMethod(), subject.accessTokenProof()))
                .getBody();

        assertThat(response.allowedContextCategories()).containsExactly(AiContextCategory.ANALYTICS);
        org.mockito.Mockito.verify(profileService).getAiPersonalizationSettings(subject);
    }

    private static ProfileAssistantProjectionProperties properties() {
        ProfileAssistantProjectionProperties properties = new ProfileAssistantProjectionProperties();
        properties.setWorkloadToken("secret");
        return properties;
    }

    private static AssistantJournalProjectionController.JournalProjectionRequest request() {
        return new AssistantJournalProjectionController.JournalProjectionRequest(
                UUID.randomUUID(), UUID.randomUUID(), "password", "a".repeat(64), 2, 4_000);
    }

    private static AssistantJournalProjectionController.JournalProjectionRequest request(ProfileSubject subject) {
        return new AssistantJournalProjectionController.JournalProjectionRequest(
                subject.accountId(), subject.sessionId(), subject.authenticationMethod(), subject.accessTokenProof(), 2, 4_000);
    }

    private static ProfileSubject subject() {
        return new ProfileSubject(UUID.randomUUID(), UUID.randomUUID(), "password", "a".repeat(64));
    }
}

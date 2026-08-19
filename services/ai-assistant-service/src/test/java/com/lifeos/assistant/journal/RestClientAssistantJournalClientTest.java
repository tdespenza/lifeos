package com.lifeos.assistant.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.lifeos.assistant.authorization.AssistantSubject;
import com.lifeos.assistant.config.AssistantProfileToolProperties;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class RestClientAssistantJournalClientTest {

    @Test
    void blankWorkloadCredentialFailsClosedBeforeNetwork() {
        AssistantProfileToolProperties properties = new AssistantProfileToolProperties();
        RestClientAssistantJournalClient client = new RestClientAssistantJournalClient(
                mock(RestClient.class), properties, new Semaphore(1));

        assertThatThrownBy(() -> client.journals(
                        new AssistantSubject(UUID.randomUUID(), UUID.randomUUID(), "password", "a".repeat(64)),
                        5,
                        4_000))
                .isInstanceOf(AssistantJournalUnavailableException.class);
        assertThat(properties.configured()).isFalse();
    }
}

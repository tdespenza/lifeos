package com.lifeos.assistant.journal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.lifeos.assistant.authorization.AssistantSubject;
import com.lifeos.assistant.config.AssistantProfileToolProperties;
import com.lifeos.assistant.observability.RequestContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/** Non-retrying workload-authenticated Profile journal adapter. */
final class RestClientAssistantJournalClient implements AssistantJournalClient {

    private static final String PATH = "/api/v1/internal/assistant/journals";
    private static final String PERSONALIZATION_PATH = "/api/v1/internal/assistant/personalization";
    private static final String WORKLOAD_IDENTITY = "X-LifeOS-Workload-Identity";
    private static final String WORKLOAD_TOKEN = "X-LifeOS-Workload-Token";

    private final RestClient restClient;
    private final AssistantProfileToolProperties properties;
    private final Semaphore permits;

    RestClientAssistantJournalClient(
            RestClient restClient, AssistantProfileToolProperties properties, Semaphore permits) {
        this.restClient = restClient;
        this.properties = properties;
        this.permits = permits;
    }

    @Override
    public JournalSnapshot journals(AssistantSubject subject, int maxEntries, int maxCharacters) {
        if (!properties.configured() || !permits.tryAcquire()) {
            throw new AssistantJournalUnavailableException();
        }
        try {
            RestClient.RequestBodySpec request = restClient.post()
                    .uri(PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(WORKLOAD_IDENTITY, properties.getWorkloadIdentity())
                    .header(WORKLOAD_TOKEN, properties.getWorkloadToken())
                    .body(new JournalRequest(
                            subject.accountId(),
                            subject.sessionId(),
                            subject.authenticationMethod(),
                            subject.accessTokenProof(),
                            maxEntries,
                            maxCharacters));
            if (RequestContext.CORRELATION_ID.isBound()) {
                request.header("X-Correlation-ID", RequestContext.CORRELATION_ID.get());
            }
            JournalResponse response = request.retrieve().body(JournalResponse.class);
            if (response == null || response.entries() == null || response.limitations() == null) {
                throw new AssistantJournalUnavailableException();
            }
            return new AssistantJournalClient.JournalSnapshot(
                    response.entries().stream()
                            .map(entry -> new AssistantJournalClient.JournalEntry(
                                    entry.id(), entry.title(), entry.content(), entry.createdAt(), entry.updatedAt(), entry.truncated()))
                            .toList(),
                    response.truncated(),
                    response.limitations());
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 401 || exception.getStatusCode().value() == 403) {
                throw new AssistantJournalDeniedException();
            }
            throw new AssistantJournalUnavailableException(exception);
        } catch (RuntimeException exception) {
            throw new AssistantJournalUnavailableException(exception);
        } finally {
            permits.release();
        }
    }

    @Override
    public PersonalizationSnapshot personalization(AssistantSubject subject) {
        if (!properties.configured() || !permits.tryAcquire()) {
            throw new AssistantJournalUnavailableException();
        }
        try {
            RestClient.RequestBodySpec request = restClient.post()
                    .uri(PERSONALIZATION_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(WORKLOAD_IDENTITY, properties.getWorkloadIdentity())
                    .header(WORKLOAD_TOKEN, properties.getWorkloadToken())
                    .body(new PersonalizationRequest(
                            subject.accountId(), subject.sessionId(), subject.authenticationMethod(), subject.accessTokenProof()));
            if (RequestContext.CORRELATION_ID.isBound()) {
                request.header("X-Correlation-ID", RequestContext.CORRELATION_ID.get());
            }
            PersonalizationResponse response = request.retrieve().body(PersonalizationResponse.class);
            if (response == null || response.allowedContextCategories() == null) {
                throw new AssistantJournalUnavailableException();
            }
            return new PersonalizationSnapshot(
                    response.consentGranted(), response.personalizationEnabled(), response.allowedContextCategories());
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 401 || exception.getStatusCode().value() == 403) {
                throw new AssistantJournalDeniedException();
            }
            throw new AssistantJournalUnavailableException(exception);
        } catch (RuntimeException exception) {
            throw new AssistantJournalUnavailableException(exception);
        } finally {
            permits.release();
        }
    }

    private record JournalRequest(
            UUID subjectId,
            UUID sessionId,
            String authenticationMethod,
            String accessTokenProof,
            int maxEntries,
            int maxCharacters) {
    }

    private record PersonalizationRequest(
            UUID subjectId, UUID sessionId, String authenticationMethod, String accessTokenProof) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record JournalResponse(
            List<JournalResponseEntry> entries, boolean truncated, List<String> limitations) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record PersonalizationResponse(
            boolean consentGranted, boolean personalizationEnabled, List<String> allowedContextCategories) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record JournalResponseEntry(
            UUID id, String title, String content, Instant createdAt, Instant updatedAt, boolean truncated) {
    }
}

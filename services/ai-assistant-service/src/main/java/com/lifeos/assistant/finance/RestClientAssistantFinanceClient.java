package com.lifeos.assistant.finance;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.lifeos.assistant.authorization.AssistantSubject;
import com.lifeos.assistant.config.AssistantFinanceToolProperties;
import com.lifeos.assistant.observability.RequestContext;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/** Non-retrying workload-authenticated Finance aggregate adapter. */
final class RestClientAssistantFinanceClient implements AssistantFinanceClient {

    private static final String PATH = "/api/v1/internal/assistant/finance-insights";
    private static final String WORKLOAD_IDENTITY = "X-LifeOS-Workload-Identity";
    private static final String WORKLOAD_TOKEN = "X-LifeOS-Workload-Token";

    private final RestClient restClient;
    private final AssistantFinanceToolProperties properties;
    private final Semaphore permits;

    RestClientAssistantFinanceClient(
            RestClient restClient, AssistantFinanceToolProperties properties, Semaphore permits) {
        this.restClient = restClient;
        this.properties = properties;
        this.permits = permits;
    }

    @Override
    public FinancialInsightSnapshot insights(
            AssistantSubject subject, LocalDate from, LocalDate to, String currency) {
        if (!properties.configured() || !permits.tryAcquire()) {
            throw new AssistantFinanceUnavailableException();
        }
        try {
            RestClient.RequestBodySpec request = restClient.post()
                    .uri(PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(WORKLOAD_IDENTITY, properties.getWorkloadIdentity())
                    .header(WORKLOAD_TOKEN, properties.getWorkloadToken())
                    .body(new FinanceInsightRequest(
                            subject.accountId(),
                            subject.sessionId(),
                            subject.authenticationMethod(),
                            subject.accessTokenProof(),
                            from,
                            to,
                            currency));
            if (RequestContext.CORRELATION_ID.isBound()) {
                request.header("X-Correlation-ID", RequestContext.CORRELATION_ID.get());
            }
            FinancialInsightResponse response = request.retrieve().body(FinancialInsightResponse.class);
            if (response == null || response.categories() == null || response.limitations() == null) {
                throw new AssistantFinanceUnavailableException();
            }
            return new AssistantFinanceClient.FinancialInsightSnapshot(
                    response.currency(),
                    response.from(),
                    response.to(),
                    response.incomeMinor(),
                    response.expenseMinor(),
                    response.netMinor(),
                    response.categories().stream()
                            .map(category -> new AssistantFinanceClient.Category(
                                    category.category(),
                                    category.incomeMinor(),
                                    category.expenseMinor(),
                                    category.netMinor()))
                            .toList(),
                    response.truncated(),
                    response.limitations());
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 401 || exception.getStatusCode().value() == 403) {
                throw new AssistantFinanceDeniedException();
            }
            throw new AssistantFinanceUnavailableException(exception);
        } catch (RuntimeException exception) {
            throw new AssistantFinanceUnavailableException(exception);
        } finally {
            permits.release();
        }
    }

    private record FinanceInsightRequest(
            UUID subjectId,
            UUID sessionId,
            String authenticationMethod,
            String accessTokenProof,
            LocalDate from,
            LocalDate to,
            String currency) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record FinancialInsightResponse(
            String currency,
            LocalDate from,
            LocalDate to,
            long incomeMinor,
            long expenseMinor,
            long netMinor,
            List<CategoryResponse> categories,
            boolean truncated,
            List<String> limitations) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CategoryResponse(String category, long incomeMinor, long expenseMinor, long netMinor) {
    }
}

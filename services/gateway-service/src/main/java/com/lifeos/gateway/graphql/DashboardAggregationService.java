package com.lifeos.gateway.graphql;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifeos.gateway.auth.GatewayAuthenticatedSubject;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Bounded dashboard fan-out. Each source is independent: an unavailable non-critical source is
 * represented explicitly instead of making the entire aggregate fail or inventing values.
 */
@Service
public class DashboardAggregationService {

    private static final int MAX_SOURCE_ROWS = 100;

    private final RestClient taskClient;
    private final RestClient calendarClient;
    private final RestClient financeClient;
    private final ObjectMapper objectMapper;
    private final DashboardGrpcClient dashboardGrpcClient;

    public DashboardAggregationService(
            RestClient.Builder builder,
            ObjectMapper objectMapper,
            @Value("${gateway.dashboard.task-upstream:http://localhost:8082}") String taskUpstream,
            @Value("${gateway.dashboard.calendar-upstream:http://localhost:8085}") String calendarUpstream,
            @Value("${gateway.dashboard.finance-upstream:http://localhost:8086}") String financeUpstream,
            ObjectProvider<DashboardGrpcClient> dashboardGrpcClientProvider) {
        this.taskClient = builder.baseUrl(taskUpstream).build();
        this.calendarClient = builder.baseUrl(calendarUpstream).build();
        this.financeClient = builder.baseUrl(financeUpstream).build();
        this.objectMapper = objectMapper;
        this.dashboardGrpcClient = dashboardGrpcClientProvider.getIfAvailable();
    }

    public DashboardSnapshot aggregate(int periodDays, String authorizationHeader) {
        return aggregate(periodDays, authorizationHeader, null);
    }

    public DashboardSnapshot aggregate(
            int periodDays, String authorizationHeader, GatewayAuthenticatedSubject authenticatedSubject) {
        int boundedPeriodDays = Math.max(1, Math.min(90, periodDays));
        if (dashboardGrpcClient != null && authenticatedSubject != null) {
            return dashboardGrpcClient.fetch(authenticatedSubject, boundedPeriodDays);
        }
        List<DashboardSnapshot.SourceStatus> sources = new ArrayList<>();
        DashboardSnapshot.TaskMetrics tasks = new DashboardSnapshot.TaskMetrics(0, 0, 0);
        DashboardSnapshot.CalendarMetrics calendar = new DashboardSnapshot.CalendarMetrics(0, "UNAVAILABLE");
        DashboardSnapshot.FinanceMetrics finance = new DashboardSnapshot.FinanceMetrics(0, 0, "UNAVAILABLE");

        SourceResult taskResult = fetch(taskClient, "/api/v1/tasks", authorizationHeader, "tasks");
        sources.add(taskResult.status());
        if (taskResult.body() != null) {
            tasks = taskMetrics(taskResult.body());
        }

        SourceResult calendarResult = fetch(calendarClient, "/api/v1/calendar/events", authorizationHeader, "calendar");
        sources.add(calendarResult.status());
        if (calendarResult.body() != null) {
            int count = rowCount(calendarResult.body());
            calendar = new DashboardSnapshot.CalendarMetrics(count, "AVAILABLE");
        }

        SourceResult financeResult = fetch(financeClient, "/api/v1/finance/budgets?page=0&size=100", authorizationHeader, "finance");
        sources.add(financeResult.status());
        if (financeResult.body() != null) {
            int budgets = rowCount(financeResult.body());
            SourceResult transactions = fetch(
                    financeClient, "/api/v1/finance/transactions?page=0&size=100", authorizationHeader, "finance");
            sources.add(transactions.status());
            int transactionCount = transactions.body() == null ? 0 : rowCount(transactions.body());
            String status = transactions.body() == null ? "PARTIAL" : "AVAILABLE";
            finance = new DashboardSnapshot.FinanceMetrics(budgets, transactionCount, status);
        }

        long available = sources.stream().filter(source -> "AVAILABLE".equals(source.status())).count();
        DashboardSnapshot.AggregateStatus aggregateStatus = available == sources.size()
                ? DashboardSnapshot.AggregateStatus.COMPLETE
                : available == 0 ? DashboardSnapshot.AggregateStatus.UNAVAILABLE : DashboardSnapshot.AggregateStatus.PARTIAL;
        return new DashboardSnapshot(
                Instant.now().toString(), boundedPeriodDays, aggregateStatus, tasks, calendar, finance, sources);
    }

    private SourceResult fetch(RestClient client, String path, String authorizationHeader, String source) {
        Instant started = Instant.now();
        try {
            String body = client.get()
                    .uri(path)
                    .header(HttpHeaders.AUTHORIZATION, authorizationHeader)
                    .retrieve()
                    .body(String.class);
            JsonNode parsed = body == null ? null : objectMapper.readTree(body);
            return new SourceResult(parsed, new DashboardSnapshot.SourceStatus(
                    source, "AVAILABLE", boundedMillis(Duration.between(started, Instant.now()))));
        } catch (RestClientException exception) {
            return new SourceResult(null, new DashboardSnapshot.SourceStatus(
                    source, "UNAVAILABLE", boundedMillis(Duration.between(started, Instant.now()))));
        } catch (Exception exception) {
            return new SourceResult(null, new DashboardSnapshot.SourceStatus(
                    source, "UNAVAILABLE", boundedMillis(Duration.between(started, Instant.now()))));
        }
    }

    private DashboardSnapshot.TaskMetrics taskMetrics(JsonNode body) {
        int total = rowCount(body);
        int active = 0;
        int completed = 0;
        for (JsonNode row : rows(body)) {
            String status = row.path("status").asText("");
            if ("ACTIVE".equalsIgnoreCase(status)) {
                active++;
            }
            if ("COMPLETED".equalsIgnoreCase(status)) {
                completed++;
            }
        }
        return new DashboardSnapshot.TaskMetrics(total, active, completed);
    }

    private int rowCount(JsonNode body) {
        JsonNode rows = body.isArray() ? body : body.path("content");
        return rows.isArray() ? Math.min(MAX_SOURCE_ROWS, rows.size()) : 0;
    }

    private List<JsonNode> rows(JsonNode body) {
        JsonNode rows = body.isArray() ? body : body.path("content");
        List<JsonNode> result = new ArrayList<>();
        if (rows.isArray()) {
            rows.elements().forEachRemaining(result::add);
        }
        return result.subList(0, Math.min(MAX_SOURCE_ROWS, result.size()));
    }

    private static int boundedMillis(Duration duration) {
        long millis = Math.max(0, Math.min(120_000, duration.toMillis()));
        return (int) millis;
    }

    private record SourceResult(JsonNode body, DashboardSnapshot.SourceStatus status) {}
}

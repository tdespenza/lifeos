package com.lifeos.assistant.analytics;

import com.lifeos.assistant.authorization.AssistantSubject;
import java.util.List;

/** Workload-authenticated boundary for bounded Analytics productivity insights. */
public interface AssistantAnalyticsClient {

    AnalyticsSnapshot insights(AssistantSubject subject, int periodDays);

    record AnalyticsSnapshot(List<Insight> insights, boolean truncated, List<String> limitations) {
    }

    record Insight(String key, int score, List<String> evidenceKeys, String sourceVersion) {
    }
}

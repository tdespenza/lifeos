package com.lifeos.assistant.analytics;

import com.lifeos.assistant.config.AssistantAnalyticsToolProperties;
import java.net.http.HttpClient;
import java.util.concurrent.Semaphore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class AssistantAnalyticsClientConfiguration {
    @Bean
    AssistantAnalyticsClient assistantAnalyticsClient(
            RestClient.Builder builder, AssistantAnalyticsToolProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(properties.getConnectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.getReadTimeout());
        return new RestClientAssistantAnalyticsClient(
                builder.baseUrl(properties.getBaseUrl()).requestFactory(requestFactory).build(),
                properties, new Semaphore(properties.getMaxConcurrentRequests(), true));
    }
}

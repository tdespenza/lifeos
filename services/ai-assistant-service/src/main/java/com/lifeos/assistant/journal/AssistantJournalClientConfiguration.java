package com.lifeos.assistant.journal;

import com.lifeos.assistant.config.AssistantProfileToolProperties;
import java.net.http.HttpClient;
import java.util.concurrent.Semaphore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** Creates the isolated, bounded Profile journal client. */
@Configuration
public class AssistantJournalClientConfiguration {

    @Bean
    AssistantJournalClient assistantJournalClient(
            RestClient.Builder builder, AssistantProfileToolProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.getConnectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.getReadTimeout());
        return new RestClientAssistantJournalClient(
                builder.baseUrl(properties.getBaseUrl()).requestFactory(requestFactory).build(),
                properties,
                new Semaphore(properties.getMaxConcurrentRequests(), true));
    }
}

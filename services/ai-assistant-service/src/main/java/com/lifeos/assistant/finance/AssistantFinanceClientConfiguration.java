package com.lifeos.assistant.finance;

import com.lifeos.assistant.config.AssistantFinanceToolProperties;
import java.net.http.HttpClient;
import java.util.concurrent.Semaphore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** Creates the isolated, bounded Finance aggregate client. */
@Configuration
public class AssistantFinanceClientConfiguration {

    @Bean
    AssistantFinanceClient assistantFinanceClient(
            RestClient.Builder builder, AssistantFinanceToolProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.getConnectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.getReadTimeout());
        return new RestClientAssistantFinanceClient(
                builder.baseUrl(properties.getBaseUrl()).requestFactory(requestFactory).build(),
                properties,
                new Semaphore(properties.getMaxConcurrentRequests(), true));
    }
}

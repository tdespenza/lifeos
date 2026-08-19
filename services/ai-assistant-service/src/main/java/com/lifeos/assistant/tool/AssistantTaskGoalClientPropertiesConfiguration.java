package com.lifeos.assistant.tool;

import com.lifeos.assistant.config.AssistantTaskGoalToolProperties;
import java.net.http.HttpClient;
import java.util.concurrent.Semaphore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** Creates the isolated, bounded client used only by confirmed task tool executions. */
@Configuration
public class AssistantTaskGoalClientPropertiesConfiguration {

    @Bean
    AssistantTaskGoalClient assistantTaskGoalClient(
            RestClient.Builder builder, AssistantTaskGoalToolProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.getConnectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.getReadTimeout());
        return new RestClientAssistantTaskGoalClient(
                builder.baseUrl(properties.getBaseUrl()).requestFactory(requestFactory).build(),
                properties,
                new Semaphore(properties.getMaxConcurrentRequests(), true));
    }
}

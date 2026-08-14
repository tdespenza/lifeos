package com.lifeos.gateway.config;

import com.lifeos.gateway.routing.GatewayRouteTable;
import java.net.http.HttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Creates the gateway's bounded outbound HTTP client.
 */
@Configuration
public class GatewayClientConfiguration {

    /**
     * Builds a RestClient with explicit connection and response-read deadlines.
     *
     * @param builder Spring's instrumented RestClient builder
     * @param properties gateway timeout configuration
     * @return configured outbound client
     */
    @Bean
    public RestClient gatewayRestClient(RestClient.Builder builder, GatewayProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.getConnectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.getReadTimeout());
        return builder.requestFactory(requestFactory).build();
    }

    /**
     * Materializes the immutable route table once at startup.
     *
     * @param properties gateway route configuration
     * @return immutable route table
     */
    @Bean
    public GatewayRouteTable gatewayRouteTable(GatewayProperties properties) {
        return new GatewayRouteTable(properties);
    }
}

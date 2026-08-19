package com.lifeos.gateway.config;

import com.lifeos.gateway.observability.W3cTraceContextClientInterceptor;
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
     * Builds the bounded RestClient used by ordinary buffered proxy routes.
     *
     * @param builder Spring's instrumented RestClient builder
     * @param properties gateway timeout configuration
     * @return configured outbound client
     */
    @Bean("gatewayBufferedRestClient")
    public RestClient gatewayBufferedRestClient(
            RestClient.Builder builder,
            GatewayProperties properties,
            W3cTraceContextClientInterceptor traceContextInterceptor) {
        return createClient(builder, properties.getConnectTimeout(), properties.getReadTimeout(), traceContextInterceptor);
    }

    /**
     * Builds a separate HTTP client for the one supported SSE route.
     *
     * <p>The client deliberately does not inherit the five-second buffered-response deadline. Its
     * finite read lifetime still bounds a connection, after which the notification client must
     * reconnect with {@code Last-Event-ID}. No other gateway route receives this client.
     *
     * @param builder Spring's instrumented RestClient builder
     * @param properties gateway timeout configuration
     * @return configured SSE-only outbound client
     */
    @Bean("gatewayStreamingRestClient")
    public RestClient gatewayStreamingRestClient(
            RestClient.Builder builder,
            GatewayProperties properties,
            W3cTraceContextClientInterceptor traceContextInterceptor) {
        GatewayProperties.Streaming streaming = properties.getStreaming();
        return createClient(builder, streaming.getConnectTimeout(), streaming.getReadLifetime(), traceContextInterceptor);
    }

    /**
     * Builds an isolated client for the exact Document Vault multipart create operation.
     *
     * <p>It has a finite deadline long enough for the service's bounded upload processing, but
     * does not inherit the SSE lifetime and is never used for an automatic retry. The route and
     * forwarder independently restrict this client to {@code POST /api/v1/documents}.
     *
     * @param builder Spring's instrumented RestClient builder
     * @param properties gateway upload configuration
     * @return configured Document Vault upload client
     */
    @Bean("gatewayDocumentUploadRestClient")
    public RestClient gatewayDocumentUploadRestClient(
            RestClient.Builder builder,
            GatewayProperties properties,
            W3cTraceContextClientInterceptor traceContextInterceptor) {
        GatewayProperties.DocumentUpload upload = properties.getDocumentUpload();
        return createClient(builder, upload.getConnectTimeout(), upload.getReadTimeout(), traceContextInterceptor);
    }

    /**
     * Builds an isolated client for the exact Media source-upload operation.
     *
     * <p>The Media service has a reviewed 60-second source-processing deadline, so this client
     * uses a finite transfer margin rather than the ordinary five-second proxy deadline. It is
     * never used for automatic retry and the route/forwarder independently restrict it to the
     * exact authenticated asset-source {@code PUT} operation.
     *
     * @param builder Spring's instrumented RestClient builder
     * @param properties gateway upload configuration
     * @return configured Media upload-only client
     */
    @Bean("gatewayMediaUploadRestClient")
    public RestClient gatewayMediaUploadRestClient(
            RestClient.Builder builder,
            GatewayProperties properties,
            W3cTraceContextClientInterceptor traceContextInterceptor) {
        GatewayProperties.MediaUpload upload = properties.getMediaUpload();
        return createClient(builder, upload.getConnectTimeout(), upload.getReadTimeout(), traceContextInterceptor);
    }

    /**
     * Builds an isolated client for the exact Media HLS manifest and segment read operations.
     *
     * <p>The client is separate from the generic buffered proxy and notification SSE lifetime.
     * The forwarder relays only reviewed HLS responses under independent admission and byte
     * ceilings; no generic Media prefix is granted this client.
     *
     * @param builder Spring's instrumented RestClient builder
     * @param properties gateway HLS configuration
     * @return configured Media HLS-only client
     */
    @Bean("gatewayMediaHlsRestClient")
    public RestClient gatewayMediaHlsRestClient(
            RestClient.Builder builder,
            GatewayProperties properties,
            W3cTraceContextClientInterceptor traceContextInterceptor) {
        GatewayProperties.MediaHls hls = properties.getMediaHls();
        return createClient(builder, hls.getConnectTimeout(), hls.getReadTimeout(), traceContextInterceptor);
    }

    /**
     * Builds the isolated AI Assistant client. The assistant performs its own bounded Identity
     * validation and provider call, so it must not inherit the ordinary buffered-route deadline.
     * This client is selected only for the exact {@code ai-assistant} route id.
     *
     * @param builder Spring's instrumented RestClient builder
     * @param properties gateway timeout configuration
     * @return configured AI Assistant outbound client
     */
    @Bean("gatewayAiAssistantRestClient")
    public RestClient gatewayAiAssistantRestClient(
            RestClient.Builder builder,
            GatewayProperties properties,
            W3cTraceContextClientInterceptor traceContextInterceptor) {
        GatewayProperties.AiAssistant assistant = properties.getAiAssistant();
        return createClient(builder, assistant.getConnectTimeout(), assistant.getReadTimeout(), traceContextInterceptor);
    }

    /** Exposes one shared W3C propagator for every bounded gateway client. */
    @Bean
    public W3cTraceContextClientInterceptor w3cTraceContextClientInterceptor() {
        return new W3cTraceContextClientInterceptor();
    }

    private static RestClient createClient(
            RestClient.Builder builder,
            java.time.Duration connectTimeout,
            java.time.Duration readTimeout,
            W3cTraceContextClientInterceptor traceContextInterceptor) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);
        return builder.clone()
                .requestFactory(requestFactory)
                .requestInterceptor(traceContextInterceptor)
                .build();
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

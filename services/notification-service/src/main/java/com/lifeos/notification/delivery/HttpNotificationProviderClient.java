package com.lifeos.notification.delivery;

import com.lifeos.notification.config.NotificationProperties;
import com.lifeos.notification.config.NotificationProviderProperties;
import com.lifeos.notification.config.NotificationProviderProperties.HttpProvider;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Shared, deliberately small HTTP adapter for configured email and push providers. Provider APIs
 * differ in production, so this sends a stable JSON command and classifies only safe status data.
 */
@Component
public class HttpNotificationProviderClient {

    private final RestClient.Builder clientBuilder;
    private final Duration timeout;
    private final NotificationProviderProperties providerProperties;

    public HttpNotificationProviderClient(
            RestClient.Builder clientBuilder,
            NotificationProperties properties,
            NotificationProviderProperties providerProperties) {
        this.clientBuilder = clientBuilder;
        this.timeout = properties.getDelivery().getProviderTimeout();
        this.providerProperties = providerProperties;
    }

    public ProviderDeliveryResult deliver(HttpProvider provider, ProviderDeliveryRequest request, boolean push) {
        if (!provider.isEnabled()) {
            if (providerProperties.isLocalDevelopmentEnabled()) {
                return ProviderDeliveryResult.delivered("local-dev-" + request.deliveryId());
            }
            return ProviderDeliveryResult.permanentFailure("PROVIDER_NOT_CONFIGURED", false);
        }
        try {
            provider.validateEnabled();
            RestClient client = newClient(provider);
            ProviderResponse response = client.post()
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + provider.getAuthorizationToken())
                    .header("Idempotency-Key", request.sourceEventId() + ":" + request.deliveryId())
                    .body(NotificationProviderPayloadRenderer.render(request))
                    .retrieve()
                    .body(ProviderResponse.class);
            return ProviderDeliveryResult.delivered(response == null ? null : response.messageId());
        } catch (RestClientResponseException exception) {
            int status = exception.getStatusCode().value();
            if (status == 408 || status == 425 || status == 429 || status >= 500) {
                return ProviderDeliveryResult.transientFailure("PROVIDER_TRANSIENT_RESPONSE");
            }
            boolean invalidDestination = push && (status == 400 || status == 404 || status == 410);
            return ProviderDeliveryResult.permanentFailure(
                    invalidDestination ? "INVALID_DESTINATION" : "PROVIDER_REJECTED", invalidDestination);
        } catch (RuntimeException exception) {
            // No exception text leaves this adapter: provider response bodies can include PII.
            return ProviderDeliveryResult.transientFailure("PROVIDER_TRANSPORT_FAILURE");
        }
    }

    private RestClient newClient(HttpProvider provider) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(timeout).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(timeout);
        return clientBuilder.baseUrl(provider.getBaseUrl().toString()).requestFactory(requestFactory).build();
    }

    private record ProviderResponse(String messageId) {
    }
}

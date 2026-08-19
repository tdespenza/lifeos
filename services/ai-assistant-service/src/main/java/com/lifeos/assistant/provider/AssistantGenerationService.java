package com.lifeos.assistant.provider;

import com.lifeos.assistant.config.AiAssistantProperties;
import java.math.BigDecimal;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** Applies a bounded virtual-thread bulkhead and timeout to a configured provider implementation. */
@Service
public class AssistantGenerationService {

    private final AssistantProvider provider;
    private final ExecutorService executor;
    private final AiAssistantProperties properties;
    private final Semaphore permits;

    public AssistantGenerationService(
            AssistantProvider provider, ExecutorService assistantProviderExecutor, AiAssistantProperties properties) {
        this.provider = provider;
        executor = assistantProviderExecutor;
        this.properties = properties;
        permits = new Semaphore(properties.getMaxConcurrentGenerations(), true);
    }

    public AssistantProviderResponse generate(AssistantProviderRequest request) {
        if (!provider.isConfigured()) {
            throw new AssistantProviderNotConfiguredException();
        }
        if (!permits.tryAcquire()) {
            throw new AssistantProviderBusyException();
        }
        Future<AssistantProviderResponse> future = executor.submit(() -> provider.generate(request));
        try {
            AssistantProviderResponse response = future.get(properties.getProviderTimeout().toMillis(), TimeUnit.MILLISECONDS);
            if (response == null
                    || !StringUtils.hasText(response.text())
                    || !StringUtils.hasText(response.providerId())
                    || !StringUtils.hasText(response.modelName())
                    || response.text().length() > 32_768
                    || !isValidConfidence(response.confidenceScore())) {
                throw new AssistantProviderFailureException(null);
            }
            return response;
        } catch (TimeoutException exception) {
            future.cancel(true);
            throw new AssistantProviderTimeoutException();
        } catch (InterruptedException exception) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new AssistantProviderFailureException(exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof AssistantProviderNotConfiguredException notConfigured) {
                throw notConfigured;
            }
            if (cause instanceof AssistantProviderFailureException providerFailure) {
                throw providerFailure;
            }
            throw new AssistantProviderFailureException(cause);
        } finally {
            permits.release();
        }
    }

    public String providerId() {
        return provider.providerId();
    }

    public String modelName() {
        return provider.modelName();
    }

    private static boolean isValidConfidence(BigDecimal confidenceScore) {
        return confidenceScore == null
                || (confidenceScore.scale() <= 4
                        && confidenceScore.compareTo(BigDecimal.ZERO) >= 0
                        && confidenceScore.compareTo(BigDecimal.ONE) <= 0);
    }
}

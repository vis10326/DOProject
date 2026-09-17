package com.doproject.client;

import com.doproject.InferenceProperties;
import com.doproject.InferenceRoute;
import com.doproject.metrics.EngineMetrics;
import java.time.Duration;
import java.util.Map;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class HttpInferenceClient implements InferenceClient {
    private final RestClient client;
    private final InferenceProperties properties;
    private final EngineMetrics metrics;

    public HttpInferenceClient(RestClient client, InferenceProperties properties, EngineMetrics metrics) {
        this.properties = properties;
        this.metrics = metrics;
        this.client = client;
    }

    @Override
    public String evaluate(String prompt) {
        return evaluate(prompt, properties.defaultRoute());
    }

    @Override
    public String evaluate(String prompt, String routeName) {
        routeName = routeName == null || routeName.isBlank() ? properties.defaultRoute() : routeName;
        InferenceRoute route = selectedRoute(routeName);
        if (route == null) {
            return prompt;
        }
        metrics.recordRouteRequest(route.name(), route.costPerRequest());
        if (route.endpoint().isBlank()) return prompt;
        return evaluateWithRetry(prompt, route.endpoint());
    }

    private InferenceRoute selectedRoute(String routeName) {
        if (properties.routes() != null && !properties.routes().isEmpty()) {
            return properties.routes().stream()
                        .filter(route -> route.name().equals(routeName))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("Configured inference route not found: "
                            + routeName));
        }
        if (properties.endpoint() == null || properties.endpoint().isBlank()) return null;
        return new InferenceRoute("default", properties.endpoint(), 0.0);
    }

    private String evaluateWithRetry(String prompt, String endpoint) {
        for (int attempt = 0; ; attempt++) {
            try {
                String response = client.post().uri(endpoint)
                        .body(Map.of("prompt", prompt))
                        .retrieve().body(String.class);
                return response == null ? "" : response;
            } catch (RestClientResponseException exception) {
                if (!isRetryable(exception) || attempt >= properties.maxRetries()) {
                    throw persistentFailure(exception);
                }
                retryAfter(attempt);
            } catch (ResourceAccessException exception) {
                if (attempt >= properties.maxRetries()) {
                    throw persistentFailure(exception);
                }
                retryAfter(attempt);
            } catch (RestClientException exception) {
                throw persistentFailure(exception);
            }
        }
    }

    private boolean isRetryable(RestClientResponseException exception) {
        int status = exception.getStatusCode().value();
        return status == 429 || status == 500 || status == 502 || status == 503 || status == 504;
    }

    private void retryAfter(int attempt) {
        metrics.recordInferenceRetry();
        Duration initial = properties.initialBackoff();
        Duration maximum = properties.maxBackoff();
        long multiplier = 1L << Math.min(attempt, 30);
        Duration delay;
        try {
            delay = initial.multipliedBy(multiplier);
        } catch (ArithmeticException exception) {
            delay = maximum;
        }
        if (delay.compareTo(maximum) > 0) delay = maximum;
        try {
            Thread.sleep(delay.toMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw persistentFailure(exception);
        }
    }

    private PersistentInferenceException persistentFailure(Exception cause) {
        metrics.recordPersistentInferenceError();
        return new PersistentInferenceException("Inference endpoint failed after retry policy", cause);
    }

    public static class PersistentInferenceException extends RuntimeException {
        public PersistentInferenceException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

package com.doproject.repository;

import com.doproject.WebhookProperties;
import com.doproject.model.WebhookRegistration;
import java.net.URI;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Component;

@Component
public class WebhookStore {
    private final ConcurrentMap<String, WebhookRegistration> registrations = new ConcurrentHashMap<>();
    private final WebhookProperties properties;

    public WebhookStore(WebhookProperties properties) {
        this.properties = properties;
    }

    public WebhookRegistration register(String jobId, URI callbackUrl) {
        if (registrations.size() >= properties.maxRegistrations() && !registrations.containsKey(jobId)) {
            throw new CapacityExceededException();
        }
        String scheme = callbackUrl.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new InvalidWebhookException();
        }
        WebhookRegistration registration = new WebhookRegistration(jobId, callbackUrl, Instant.now());
        registrations.put(jobId, registration);
        return registration;
    }

    public Optional<WebhookRegistration> find(String jobId) {
        return Optional.ofNullable(registrations.get(jobId));
    }

    public static class CapacityExceededException extends RuntimeException { }
    public static class InvalidWebhookException extends RuntimeException { }
}

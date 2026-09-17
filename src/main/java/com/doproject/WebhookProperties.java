package com.doproject;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "webhook")
public record WebhookProperties(Duration timeout, int maxRegistrations, int deliveryThreads, List<String> allowedHosts) {
    public WebhookProperties {
        if (timeout == null || timeout.isNegative() || maxRegistrations < 1 || deliveryThreads < 1) {
            throw new IllegalArgumentException("Webhook settings are invalid");
        }
        allowedHosts = allowedHosts == null ? List.of() : List.copyOf(allowedHosts);
    }
}

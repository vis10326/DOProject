package com.doproject;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProperties(String apiKey) {
    public AppProperties {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("app.api-key must be configured");
        }
    }
}

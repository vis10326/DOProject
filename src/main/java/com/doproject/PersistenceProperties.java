package com.doproject;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "persistence")
public record PersistenceProperties(String type, Path directory, String bucket, String endpoint,
                                    String region, String accessKey, String secretKey) {
    public PersistenceProperties {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("Persistence type must be configured");
        }
    }
}

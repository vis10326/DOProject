package com.doproject;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "batch")
public record BatchProperties(int maxPrompts, int chunkSize, int workerThreads, int queueCapacity, int maxJobs,
                              String inputDirectory) {
    public BatchProperties {
        if (maxPrompts < 1 || chunkSize < 1 || workerThreads < 1 || queueCapacity < 1 || maxJobs < 1) {
            throw new IllegalArgumentException("Batch limits must be positive");
        }
        if (inputDirectory == null || inputDirectory.isBlank()) {
            throw new IllegalArgumentException("Input directory must be configured");
        }
    }
}

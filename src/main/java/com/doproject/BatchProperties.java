package com.doproject;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "batch")
public record BatchProperties(int maxPrompts, int chunkSize, int workerThreads, int ingestionThreads, int queueCapacity, int maxJobs,
                              int highThroughputPromptThreshold, String highThroughputRoute, String inputDirectory) {
    public BatchProperties {
    if (maxPrompts < 1 || chunkSize < 1 || workerThreads < 1 || ingestionThreads < 1
        || queueCapacity < 1 || maxJobs < 1 || highThroughputPromptThreshold < 1) {
            throw new IllegalArgumentException("Batch limits must be positive");
        }
        if (highThroughputRoute == null || highThroughputRoute.isBlank()
                || inputDirectory == null || inputDirectory.isBlank()) {
            throw new IllegalArgumentException("Batch route and input directory must be configured");
        }
    }
}

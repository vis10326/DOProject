package com.doproject;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "inference")
public record InferenceProperties(String endpoint, Duration timeout) {
}

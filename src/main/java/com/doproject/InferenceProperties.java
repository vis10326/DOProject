package com.doproject;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties(prefix = "inference")
public record InferenceProperties(String endpoint, Duration timeout, int maxRetries,
                                  Duration initialBackoff, Duration maxBackoff,
                                  String defaultRoute, List<InferenceRoute> routes) {
	public InferenceProperties(String endpoint, Duration timeout, int maxRetries,
	                           Duration initialBackoff, Duration maxBackoff) {
		this(endpoint, timeout, maxRetries, initialBackoff, maxBackoff, "cheap", List.of());
	}

	@ConstructorBinding
	public InferenceProperties {
		if (maxRetries < 0 || initialBackoff == null || initialBackoff.isNegative()
				|| maxBackoff == null || maxBackoff.isNegative()) {
			throw new IllegalArgumentException("Inference retry settings are invalid");
		}
		if (defaultRoute == null || defaultRoute.isBlank() || routes == null) {
			throw new IllegalArgumentException("Inference route settings are invalid");
		}
		routes = List.copyOf(routes);
	}
}

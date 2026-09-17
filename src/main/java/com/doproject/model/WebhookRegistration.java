package com.doproject.model;

import java.net.URI;
import java.time.Instant;

public record WebhookRegistration(String jobId, URI callbackUrl, Instant registeredAt) {
}

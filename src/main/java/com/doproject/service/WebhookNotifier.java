package com.doproject.service;

import com.doproject.WebhookProperties;
import com.doproject.model.Job;
import com.doproject.repository.WebhookStore;
import java.util.Map;
import java.util.concurrent.Executor;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class WebhookNotifier {
    private final WebhookStore store;
    private final RestClient client;
    private final Executor deliveryExecutor;

    public WebhookNotifier(WebhookStore store, RestClient.Builder builder,
                           WebhookProperties properties,
                           @Qualifier("webhookDeliveryExecutor") Executor deliveryExecutor) {
        this.store = store;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.timeout());
        factory.setReadTimeout(properties.timeout());
        this.client = builder.requestFactory(factory).build();
        this.deliveryExecutor = deliveryExecutor;
    }

    public void notifyAsync(Job job) {
        store.find(job.id()).ifPresent(registration -> deliveryExecutor.execute(() -> {
            try {
                client.post().uri(registration.callbackUrl())
                        .body(Map.of("jobId", job.id(), "status", job.status().name(),
                                "total", job.total(), "completed", job.completed(),
                                "succeeded", job.succeeded(), "failed", job.failed()))
                        .retrieve().toBodilessEntity();
            } catch (RuntimeException ignored) {
                // Webhook delivery must not affect batch execution.
            }
        }));
    }
}

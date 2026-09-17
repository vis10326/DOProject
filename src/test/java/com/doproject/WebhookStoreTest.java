package com.doproject;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.doproject.repository.WebhookStore;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class WebhookStoreTest {
    @Test
    void rejectsCallbackHostThatIsNotAllowlisted() {
        WebhookStore store = new WebhookStore(
                new WebhookProperties(Duration.ofSeconds(1), 10, 1, List.of("example.com")));
        assertThatThrownBy(() -> store.register("job-1", URI.create("http://evil.example/callback")))
                .isInstanceOf(WebhookStore.InvalidWebhookException.class);
    }

    @Test
    void acceptsAllowlistedHost() {
        WebhookStore store = new WebhookStore(
                new WebhookProperties(Duration.ofSeconds(1), 10, 1, List.of("example.com")));
        store.register("job-1", URI.create("https://example.com/callback"));
    }
}

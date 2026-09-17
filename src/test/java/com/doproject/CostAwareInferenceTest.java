package com.doproject;

import static org.assertj.core.api.Assertions.assertThat;

import com.doproject.client.HttpInferenceClient;
import com.doproject.metrics.EngineMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class CostAwareInferenceTest {
    @Test
    void usesCheapRouteForHighThroughputLocalTestingAndRecordsCost() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        HttpInferenceClient client = new HttpInferenceClient(RestClient.builder().build(),
                new InferenceProperties(null, Duration.ofSeconds(1), 0, Duration.ZERO, Duration.ZERO,
                        "cheap", List.of(new InferenceRoute("cheap", "", 0.001),
                                new InferenceRoute("premium", "https://premium", 0.02))),
                new EngineMetrics(registry));

        assertThat(client.evaluate("prompt")).isEqualTo("prompt");
        assertThat(registry.get("batch.inference.route.requests").tag("route", "cheap").counter().count())
                .isEqualTo(1);
        assertThat(registry.get("batch.inference.estimated.cost").tag("route", "cheap").counter().count())
                .isEqualTo(0.001);
    }
}

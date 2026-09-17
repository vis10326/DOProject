package com.doproject;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.doproject.client.HttpInferenceClient;
import com.doproject.metrics.EngineMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

class HttpInferenceClientTest {
    @Test
    void retriesTransientEndpointFailureWithBackoff() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        HttpInferenceClient client = new HttpInferenceClient(restClient,
                new InferenceProperties("http://inference", Duration.ofSeconds(1), 1,
                        Duration.ZERO, Duration.ZERO), new EngineMetrics(registry));

        server.expect(requestTo("http://inference"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(requestTo("http://inference"))
                .andRespond(withSuccess("answer", MediaType.TEXT_PLAIN));

        assertThat(client.evaluate("prompt")).isEqualTo("answer");
        assertThat(registry.get("batch.inference.retries").counter().count()).isEqualTo(1);
        server.verify();
    }

    @Test
    void recordsPersistentFailureAfterRetryLimit() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        HttpInferenceClient client = new HttpInferenceClient(restClient,
                new InferenceProperties("http://inference", Duration.ofSeconds(1), 1,
                        Duration.ZERO, Duration.ZERO), new EngineMetrics(registry));

        server.expect(requestTo("http://inference"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(requestTo("http://inference"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> client.evaluate("prompt"))
                .isInstanceOf(HttpInferenceClient.PersistentInferenceException.class);
        assertThat(registry.get("batch.inference.retries").counter().count()).isEqualTo(1);
        assertThat(registry.get("batch.inference.persistent.errors").counter().count()).isEqualTo(1);
        server.verify();
    }
}

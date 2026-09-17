package com.doproject.client;

import com.doproject.InferenceProperties;
import java.time.Duration;
import java.util.Map;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class HttpInferenceClient implements InferenceClient {
    private final RestClient client;
    private final InferenceProperties properties;

    public HttpInferenceClient(RestClient client, InferenceProperties properties) {
        this.properties = properties;
        Duration timeout = properties.timeout() == null ? Duration.ofSeconds(10) : properties.timeout();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        this.client = client.mutate().requestFactory(factory).build();
    }

    @Override
    public String evaluate(String prompt) {
        if (properties.endpoint() == null || properties.endpoint().isBlank()) {
            return prompt;
        }
        String response = client.post().uri(properties.endpoint())
                .body(Map.of("prompt", prompt))
                .retrieve().body(String.class);
        return response == null ? "" : response;
    }
}

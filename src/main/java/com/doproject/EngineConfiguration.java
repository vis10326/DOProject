package com.doproject;

import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties({BatchProperties.class, InferenceProperties.class, PersistenceProperties.class,
    WebhookProperties.class})
public class EngineConfiguration {
    @Bean(destroyMethod = "shutdown")
    ThreadPoolExecutor workerExecutor(BatchProperties properties) {
        return new ThreadPoolExecutor(
                properties.workerThreads(), properties.workerThreads(), 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(properties.queueCapacity()),
                new ThreadPoolExecutor.AbortPolicy());
    }

    @Bean(destroyMethod = "shutdown")
    ThreadPoolExecutor ingestionExecutor(BatchProperties properties) {
        return new ThreadPoolExecutor(
                properties.ingestionThreads(), properties.ingestionThreads(), 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(properties.queueCapacity()),
                new ThreadPoolExecutor.AbortPolicy());
    }

    @Bean(destroyMethod = "shutdown")
    ThreadPoolExecutor webhookDeliveryExecutor(WebhookProperties properties) {
        return new ThreadPoolExecutor(
                properties.deliveryThreads(), properties.deliveryThreads(), 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(properties.maxRegistrations()),
                new ThreadPoolExecutor.DiscardPolicy());
    }

    @Bean
    RestClient restClient(RestClient.Builder builder, InferenceProperties properties) {
        Duration timeout = properties.timeout() == null ? Duration.ofSeconds(10) : properties.timeout();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        return builder.requestFactory(factory).build();
    }
}

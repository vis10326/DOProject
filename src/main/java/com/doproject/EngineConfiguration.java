package com.doproject;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties({BatchProperties.class, InferenceProperties.class})
public class EngineConfiguration {
    @Bean(destroyMethod = "shutdown")
    ThreadPoolExecutor batchExecutor(BatchProperties properties) {
        return new ThreadPoolExecutor(
                properties.workerThreads(), properties.workerThreads(), 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(properties.queueCapacity()),
                new ThreadPoolExecutor.AbortPolicy());
    }

    @Bean
    RestClient restClient(RestClient.Builder builder) {
        return builder.build();
    }
}

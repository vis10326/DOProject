package com.doproject;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.doproject.client.InferenceClient;
import com.doproject.model.Job;
import com.doproject.model.JobStatus;
import com.doproject.model.PromptResult;
import com.doproject.metrics.EngineMetrics;
import com.doproject.repository.JobStore;
import com.doproject.repository.JobPersistence;
import com.doproject.service.BatchService;
import com.doproject.service.WebhookNotifier;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class BatchServiceTest {
    private final ThreadPoolExecutor workerExecutor = new ThreadPoolExecutor(
            1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(2));
    private final ThreadPoolExecutor ingestionExecutor = new ThreadPoolExecutor(
            1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(2));
    private final SimpleMeterRegistry metricsRegistry = new SimpleMeterRegistry();
    private final EngineMetrics metrics = new EngineMetrics(metricsRegistry);
    private final JobStore jobStore = new JobStore(mock(JobPersistence.class));
    private final WebhookNotifier webhookNotifier = mock(WebhookNotifier.class);
    private final InferenceProperties inferenceProperties = new InferenceProperties(
            "", Duration.ofSeconds(1), 0, Duration.ZERO, Duration.ZERO);

    @AfterEach
    void shutdown() {
        workerExecutor.shutdownNow();
        ingestionExecutor.shutdownNow();
    }

    @Test
    void isolatesInferenceFailureAndProcessesRemainingPrompts() throws Exception {
        InferenceClient client = prompt -> {
            if (prompt.equals("bad")) throw new IllegalStateException("endpoint failed");
            return "answer:" + prompt;
        };
        BatchService service = service(client, workerExecutor, 2);

        Job job = service.submit(new MockMultipartFile("file", "batch.json", "application/json",
            "[\"one\",\"bad\",\"three\"]".getBytes()), "cheap");
        waitForCompletion(job);

        assertThat(job.status()).isEqualTo(JobStatus.COMPLETED_WITH_ERRORS);
        assertThat(job.completed()).isEqualTo(3);
        assertThat(job.succeeded()).isEqualTo(2);
        assertThat(job.failed()).isEqualTo(1);
        assertThat(job.orderedResults()).extracting(PromptResult::output)
                .containsExactly("answer:one", null, "answer:three");
    }

    @Test
    void recordsOutOfMemoryErrorAndFailsTheJob() throws Exception {
        InferenceClient client = prompt -> { throw new OutOfMemoryError("simulated"); };
        BatchService service = service(client, workerExecutor, 2);

        Job job = service.submit(new MockMultipartFile("file", "batch.json", "application/json",
            "[\"one\"]".getBytes()), "cheap");
        waitForCompletion(job);

        assertThat(job.status()).isEqualTo(JobStatus.FAILED);
        assertThat(metricsRegistry.get("batch.oom.errors").counter().count()).isEqualTo(1);
    }

    @Test
    void usesStableDefaultCheapRouteForTheJob() throws Exception {
        InferenceClient client = prompt -> prompt;
        BatchService service = service(client, workerExecutor, 2);

        Job job = service.submit(new MockMultipartFile("file", "batch.json", "application/json",
                "[\"one\",\"two\"]".getBytes()), null);
        waitForCompletion(job);

        assertThat(job.routeName()).isEqualTo("cheap");
        assertThat(job.status()).isEqualTo(JobStatus.COMPLETED);
    }

    @Test
    void fansChunksAcrossTheWorkerPool() throws Exception {
        ThreadPoolExecutor workers = new ThreadPoolExecutor(
                2, 2, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(8));
        CyclicBarrier barrier = new CyclicBarrier(2);
        InferenceClient client = prompt -> {
            try {
                barrier.await(1, TimeUnit.SECONDS);
                return "ok";
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        };
        BatchService service = service(client, workers, 1);
        try {
            Job job = service.submit(new MockMultipartFile("file", "batch.json", "application/json",
                    "[\"one\",\"two\"]".getBytes()), "cheap");
            waitForCompletion(job);
            assertThat(job.status()).isEqualTo(JobStatus.COMPLETED);
            assertThat(job.succeeded()).isEqualTo(2);
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void limitsInFlightChunksPerJob() throws Exception {
        ThreadPoolExecutor workers = new ThreadPoolExecutor(
                2, 2, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(8));
        AtomicInteger current = new AtomicInteger();
        AtomicInteger max = new AtomicInteger();
        InferenceClient client = prompt -> {
            int inFlight = current.incrementAndGet();
            max.accumulateAndGet(inFlight, Math::max);
            try {
                Thread.sleep(50);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            } finally {
                current.decrementAndGet();
            }
            return "ok";
        };
        BatchService service = service(client, workers, 1, 1);
        try {
            Job job = service.submit(new MockMultipartFile("file", "batch.json", "application/json",
                    "[\"one\",\"two\"]".getBytes()), "cheap");
            waitForCompletion(job);
            assertThat(job.status()).isEqualTo(JobStatus.COMPLETED);
            assertThat(max.get()).isEqualTo(1);
        } finally {
            workers.shutdownNow();
        }
    }

    private BatchService service(InferenceClient client, ThreadPoolExecutor workers, int chunkSize) {
        return service(client, workers, chunkSize, 8);
    }

    private BatchService service(InferenceClient client, ThreadPoolExecutor workers, int chunkSize, int maxInFlight) {
        return new BatchService(new ObjectMapper(),
                new BatchProperties(10, chunkSize, 1, 1, 8, 10, 2, "cheap", "workspace-input", maxInFlight),
                inferenceProperties, jobStore, workers, ingestionExecutor, client, metrics, webhookNotifier);
    }

    private void waitForCompletion(Job job) throws InterruptedException {
        for (int attempt = 0; attempt < 200 && job.status() != JobStatus.COMPLETED_WITH_ERRORS
                && job.status() != JobStatus.COMPLETED && job.status() != JobStatus.FAILED; attempt++) {
            Thread.sleep(10);
        }
    }
}

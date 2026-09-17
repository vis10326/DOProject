package com.doproject.service;

import com.doproject.BatchProperties;
import com.doproject.InferenceProperties;
import com.doproject.client.InferenceClient;
import com.doproject.model.Job;
import com.doproject.model.JobStatus;
import com.doproject.model.PromptChunk;
import com.doproject.model.PromptResult;
import com.doproject.metrics.EngineMetrics;
import com.doproject.repository.JobStore;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class BatchService {
    private static final Logger log = LoggerFactory.getLogger(BatchService.class);
    private final ObjectMapper objectMapper;
    private final BatchProperties properties;
    private final InferenceProperties inferenceProperties;
    private final JobStore jobStore;
    private final ThreadPoolExecutor workerExecutor;
    private final ThreadPoolExecutor ingestionExecutor;
    private final InferenceClient inferenceClient;
    private final EngineMetrics metrics;
    private final WebhookNotifier webhookNotifier;

    public BatchService(ObjectMapper objectMapper, BatchProperties properties, InferenceProperties inferenceProperties,
                        JobStore jobStore, @Qualifier("workerExecutor") ThreadPoolExecutor workerExecutor,
                        @Qualifier("ingestionExecutor") ThreadPoolExecutor ingestionExecutor,
                        InferenceClient inferenceClient, EngineMetrics metrics, WebhookNotifier webhookNotifier) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.inferenceProperties = inferenceProperties;
        this.jobStore = jobStore;
        this.workerExecutor = workerExecutor;
        this.ingestionExecutor = ingestionExecutor;
        this.inferenceClient = inferenceClient;
        this.metrics = metrics;
        this.webhookNotifier = webhookNotifier;
    }

    public Job submit(MultipartFile file, String routeName) throws IOException {
        return submit(file::getInputStream, routeName);
    }

    public Job submitLocal(String fileName, String routeName) throws IOException {
        Path inputDirectory = Path.of(properties.inputDirectory()).toAbsolutePath().normalize();
        Path inputFile = inputDirectory.resolve(fileName).normalize();
        if (!inputFile.startsWith(inputDirectory) || Files.isDirectory(inputFile)) {
            throw new InvalidBatchException("Input file must be inside the configured workspace directory");
        }
        return submit(() -> Files.newInputStream(inputFile), routeName);
    }

    private Job submit(InputStreamSupplier inputSupplier, String routeName) {
        String selectedRoute = routeName == null || routeName.isBlank()
                ? inferenceProperties.defaultRoute() : routeName;
        Job job = jobStore.create(0, properties.maxJobs(), selectedRoute);
        try {
            ingestionExecutor.execute(() -> ingest(job, inputSupplier));
        } catch (RejectedExecutionException exception) {
            log.warn("Ingestion pool full, rejecting job {}", job.id());
            job.fail();
            persist(job);
            jobStore.remove(job.id());
            throw new QueueFullException();
        }
        return job;
    }

    private void ingest(Job job, InputStreamSupplier inputSupplier) {
        job.start();
        persist(job);
        Semaphore inFlight = new Semaphore(properties.maxInFlightPerJob());
        try (InputStream input = inputSupplier.open(); JsonParser parser = objectMapper.getFactory().createParser(input)) {
            if (parser.nextToken() != JsonToken.START_ARRAY) {
                throw new InvalidBatchException("Prompt file must contain a JSON array");
            }
            List<String> chunk = new ArrayList<>(properties.chunkSize());
            int index = 0;
            while (parser.nextToken() != JsonToken.END_ARRAY) {
                if (parser.currentToken() != JsonToken.VALUE_STRING) {
                    throw new InvalidBatchException("Prompts must be strings");
                }
                String prompt = parser.getValueAsString();
                validatePrompt(prompt);
                if (index >= properties.maxPrompts()) {
                    throw new InvalidBatchException("Prompt count exceeds configured limit");
                }
                chunk.add(prompt);
                job.setTotal(index + 1);
                index++;
                if (chunk.size() == properties.chunkSize()) {
                    submitChunk(job, PromptChunk.data(chunk, index - chunk.size()), inFlight);
                    chunk.clear();
                }
            }
            if (chunk.isEmpty() && index == 0) {
                throw new InvalidBatchException("Prompt array must not be empty");
            }
            if (!chunk.isEmpty()) submitChunk(job, PromptChunk.data(chunk, index - chunk.size()), inFlight);
        } catch (OutOfMemoryError error) {
            metrics.recordOutOfMemory();
            log.error("Job {} exhausted memory during ingestion", job.id(), error);
            job.fail();
            persist(job);
        } catch (InvalidBatchException exception) {
            log.warn("Invalid batch for job {}: {}", job.id(), exception.getMessage());
            job.fail();
            persist(job);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            log.warn("Ingestion interrupted for job {}", job.id());
            job.fail();
            persist(job);
        } catch (Exception exception) {
            log.error("Ingestion failed for job {}", job.id(), exception);
            job.fail();
            persist(job);
        } finally {
            if (job.decrementPending() == 0) complete(job);
        }
    }

    private void submitChunk(Job job, PromptChunk chunk, Semaphore inFlight) throws InterruptedException {
        inFlight.acquire();
        job.incrementPending();
        try {
            workerExecutor.execute(() -> {
                try {
                    if (job.status() != JobStatus.FAILED) {
                        processChunk(job, chunk.prompts(), chunk.startIndex());
                    }
                } catch (OutOfMemoryError error) {
                    metrics.recordOutOfMemory();
                    log.error("Job {} exhausted memory while processing a chunk", job.id(), error);
                    job.fail();
                } catch (RuntimeException exception) {
                    log.error("Job {} chunk processing failed", job.id(), exception);
                    job.fail();
                } finally {
                    persist(job);
                    inFlight.release();
                    if (job.decrementPending() == 0) complete(job);
                }
            });
        } catch (RuntimeException exception) {
            inFlight.release();
            job.decrementPending();
            throw exception;
        }
    }

    private void complete(Job job) {
        if (job.status() != JobStatus.FAILED) job.finish();
        boolean saved = persist(job);
        webhookNotifier.notifyAsync(job);
        if (saved) jobStore.remove(job.id());
    }

    private void processChunk(Job job, List<String> prompts, int startIndex) {
        for (int offset = 0; offset < prompts.size(); offset++) {
            String prompt = prompts.get(offset);
            try {
                job.record(PromptResult.success(startIndex + offset, prompt,
                    inferenceClient.evaluate(prompt, job.routeName())));
            } catch (Exception exception) {
                log.warn("Prompt {} failed for job {}: {}", startIndex + offset, job.id(), exception.getMessage());
                job.record(PromptResult.failure(startIndex + offset, prompt, exception));
            }
        }
    }

    private boolean persist(Job job) {
        try {
            jobStore.save(job);
            return true;
        } catch (RuntimeException exception) {
            log.error("Failed to persist job {}", job.id(), exception);
            job.fail();
            return false;
        }
    }

    private void validatePrompt(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            throw new InvalidBatchException("Prompts must be non-blank strings");
        }
    }

    @FunctionalInterface
    private interface InputStreamSupplier {
        InputStream open() throws IOException;
    }

    public static class InvalidBatchException extends RuntimeException {
        public InvalidBatchException(String message) { super(message); }
    }

    public static class QueueFullException extends RuntimeException { }
}

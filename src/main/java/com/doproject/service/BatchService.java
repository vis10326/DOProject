package com.doproject.service;

import com.doproject.BatchProperties;
import com.doproject.client.InferenceClient;
import com.doproject.model.Job;
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
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class BatchService {
    private final ObjectMapper objectMapper;
    private final BatchProperties properties;
    private final JobStore jobStore;
    private final ThreadPoolExecutor workerExecutor;
    private final ThreadPoolExecutor ingestionExecutor;
    private final InferenceClient inferenceClient;
    private final EngineMetrics metrics;
    private final WebhookNotifier webhookNotifier;

    public BatchService(ObjectMapper objectMapper, BatchProperties properties, JobStore jobStore,
                        @Qualifier("workerExecutor") ThreadPoolExecutor workerExecutor,
                        @Qualifier("ingestionExecutor") ThreadPoolExecutor ingestionExecutor,
                        InferenceClient inferenceClient, EngineMetrics metrics, WebhookNotifier webhookNotifier) {
        this.objectMapper = objectMapper;
        this.properties = properties;
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
        String selectedRoute = routeName == null || routeName.isBlank() ? null : routeName;
        Job job = jobStore.create(0, properties.maxJobs(), properties.queueCapacity(), selectedRoute);
        try {
            workerExecutor.execute(() -> consume(job));
            ingestionExecutor.execute(() -> ingest(job, inputSupplier));
        } catch (RejectedExecutionException exception) {
            job.fail();
            signalEnd(job.promptQueue());
            jobStore.remove(job.id());
            throw new QueueFullException();
        }
        return job;
    }

    private void ingest(Job job, InputStreamSupplier inputSupplier) {
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
                if (job.routeName() == null && index >= properties.highThroughputPromptThreshold()) {
                    job.setRouteName(properties.highThroughputRoute());
                }
                if (chunk.size() == properties.chunkSize()) {
                    job.promptQueue().put(PromptChunk.data(chunk, index - chunk.size()));
                    chunk.clear();
                }
            }
            if (chunk.isEmpty() && index == 0) {
                throw new InvalidBatchException("Prompt array must not be empty");
            }
            if (!chunk.isEmpty()) job.promptQueue().put(PromptChunk.data(chunk, index - chunk.size()));
            job.promptQueue().put(PromptChunk.end());
        } catch (OutOfMemoryError error) {
            metrics.recordOutOfMemory();
            job.fail();
            persist(job);
            signalEnd(job.promptQueue());
        } catch (Exception exception) {
            job.fail();
            persist(job);
            signalEnd(job.promptQueue());
        }
    }

    private void consume(Job job) {
        if (job.status() != com.doproject.model.JobStatus.FAILED) job.start();
        persist(job);
        try {
            BlockingQueue<PromptChunk> queue = job.promptQueue();
            while (true) {
                PromptChunk chunk = queue.take();
                if (chunk.terminal()) break;
                processChunk(job, chunk.prompts(), chunk.startIndex());
            }
            if (job.status() != com.doproject.model.JobStatus.FAILED) job.finish();
            persist(job);
            webhookNotifier.notifyAsync(job);
        } catch (OutOfMemoryError error) {
            metrics.recordOutOfMemory();
            job.fail();
            persist(job);
            webhookNotifier.notifyAsync(job);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            job.fail();
            persist(job);
            webhookNotifier.notifyAsync(job);
        }
    }

    private void signalEnd(BlockingQueue<PromptChunk> queue) {
        try {
            queue.put(PromptChunk.end());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private void processChunk(Job job, List<String> prompts, int startIndex) {
        for (int offset = 0; offset < prompts.size(); offset++) {
            String prompt = prompts.get(offset);
            try {
                job.record(PromptResult.success(startIndex + offset, prompt,
                    inferenceClient.evaluate(prompt, job.routeName())));
            } catch (Exception exception) {
                job.record(PromptResult.failure(startIndex + offset, prompt, exception));
            }
            persist(job);
        }
    }

    private void persist(Job job) {
        try {
            jobStore.save(job);
        } catch (RuntimeException exception) {
            job.fail();
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

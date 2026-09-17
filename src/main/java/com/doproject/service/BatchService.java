package com.doproject.service;

import com.doproject.BatchProperties;
import com.doproject.client.InferenceClient;
import com.doproject.model.Job;
import com.doproject.model.PromptResult;
import com.doproject.repository.JobStore;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class BatchService {
    private final ObjectMapper objectMapper;
    private final BatchProperties properties;
    private final JobStore jobStore;
    private final ThreadPoolExecutor executor;
    private final InferenceClient inferenceClient;

    public BatchService(ObjectMapper objectMapper, BatchProperties properties, JobStore jobStore,
                        ThreadPoolExecutor executor, InferenceClient inferenceClient) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.jobStore = jobStore;
        this.executor = executor;
        this.inferenceClient = inferenceClient;
    }

    public Job submit(MultipartFile file) throws IOException {
        return submit(file.getInputStream());
    }

    public Job submitLocal(String fileName) throws IOException {
        Path inputDirectory = Path.of(properties.inputDirectory()).toAbsolutePath().normalize();
        Path inputFile = inputDirectory.resolve(fileName).normalize();
        if (!inputFile.startsWith(inputDirectory) || Files.isDirectory(inputFile)) {
            throw new InvalidBatchException("Input file must be inside the configured workspace directory");
        }
        try (InputStream input = Files.newInputStream(inputFile)) {
            return submit(input);
        }
    }

    private Job submit(InputStream input) throws IOException {
        List<String> prompts = objectMapper.readValue(input, new TypeReference<>() {});
        validate(prompts);
        Job job = jobStore.create(prompts.size(), properties.maxJobs());
        try {
            executor.execute(() -> process(job, prompts));
        } catch (RejectedExecutionException exception) {
            jobStore.remove(job.id());
            throw new QueueFullException();
        }
        return job;
    }

    private void validate(List<String> prompts) {
        if (prompts == null || prompts.isEmpty()) throw new InvalidBatchException("Prompt array must not be empty");
        if (prompts.size() > properties.maxPrompts()) {
            throw new InvalidBatchException("Prompt count exceeds configured limit");
        }
        if (prompts.stream().anyMatch(prompt -> prompt == null || prompt.isBlank())) {
            throw new InvalidBatchException("Prompts must be non-blank strings");
        }
    }

    private void process(Job job, List<String> prompts) {
        job.start();
        try {
            for (int start = 0; start < prompts.size(); start += properties.chunkSize()) {
                int end = Math.min(start + properties.chunkSize(), prompts.size());
                for (int index = start; index < end; index++) {
                    String prompt = prompts.get(index);
                    try {
                        job.record(PromptResult.success(index, prompt, inferenceClient.evaluate(prompt)));
                    } catch (Exception exception) {
                        job.record(PromptResult.failure(index, prompt, exception));
                    }
                }
            }
            job.finish();
        } catch (RuntimeException exception) {
            job.fail();
        }
    }

    public static class InvalidBatchException extends RuntimeException {
        public InvalidBatchException(String message) { super(message); }
    }

    public static class QueueFullException extends RuntimeException { }
}

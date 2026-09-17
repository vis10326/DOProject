package com.doproject;

import static org.assertj.core.api.Assertions.assertThat;

import com.doproject.client.InferenceClient;
import com.doproject.model.Job;
import com.doproject.model.JobStatus;
import com.doproject.model.PromptResult;
import com.doproject.repository.JobStore;
import com.doproject.service.BatchService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class BatchServiceTest {
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(
            1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(2));

    @AfterEach
    void shutdown() {
        executor.shutdownNow();
    }

    @Test
    void isolatesInferenceFailureAndProcessesRemainingPrompts() throws Exception {
        InferenceClient client = prompt -> {
            if (prompt.equals("bad")) throw new IllegalStateException("endpoint failed");
            return "answer:" + prompt;
        };
        BatchService service = new BatchService(
                new ObjectMapper(), new BatchProperties(10, 2, 1, 2, 10, "workspace-input"),
                new JobStore(), executor, client);

        Job job = service.submit(new MockMultipartFile("file", "batch.json", "application/json",
                "[\"one\",\"bad\",\"three\"]".getBytes()));
        waitForCompletion(job);

        assertThat(job.status()).isEqualTo(JobStatus.COMPLETED_WITH_ERRORS);
        assertThat(job.completed()).isEqualTo(3);
        assertThat(job.succeeded()).isEqualTo(2);
        assertThat(job.failed()).isEqualTo(1);
        assertThat(job.orderedResults()).extracting(PromptResult::output)
                .containsExactly("answer:one", null, "answer:three");
    }

    private void waitForCompletion(Job job) throws InterruptedException {
        for (int attempt = 0; attempt < 100 && job.completed() < job.total(); attempt++) {
            Thread.sleep(10);
        }
    }
}

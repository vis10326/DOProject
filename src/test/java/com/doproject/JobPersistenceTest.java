package com.doproject;

import static org.assertj.core.api.Assertions.assertThat;

import com.doproject.model.Job;
import com.doproject.model.JobStatus;
import com.doproject.model.PromptResult;
import com.doproject.repository.FileJobPersistence;
import com.doproject.repository.JobStore;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

class JobPersistenceTest {
    @TempDir Path directory;

    @Test
    void savesAndRestoresJobSnapshot() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        FileJobPersistence persistence = new FileJobPersistence(objectMapper,
                new PersistenceProperties("file", directory, "", "", "", "", ""));
        Job job = new Job("job-1", 1, "premium");
        job.start();
        job.record(PromptResult.success(0, "prompt", "answer"));
        job.finish();

        persistence.save(job.snapshot());

        assertThat(Files.exists(directory.resolve("job-1.json"))).isTrue();
        assertThat(persistence.find("job-1")).isPresent()
                .get().extracting(snapshot -> snapshot.status()).isEqualTo(JobStatus.COMPLETED);
    }

    @Test
    void recoverInterruptedJobsMarksRunningSnapshotsFailed() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        FileJobPersistence persistence = new FileJobPersistence(objectMapper,
                new PersistenceProperties("file", directory, "", "", "", "", ""));
        Job job = new Job("job-1", 1, "cheap");
        job.start();
        persistence.save(job.snapshot());

        JobStore store = new JobStore(persistence);
        store.recoverInterruptedJobs();

        assertThat(persistence.find("job-1")).isPresent()
                .get().extracting(snapshot -> snapshot.status()).isEqualTo(JobStatus.FAILED);
        assertThat(store.find("job-1")).isPresent()
                .get().extracting(Job::createdAt).isEqualTo(job.createdAt());
    }
}

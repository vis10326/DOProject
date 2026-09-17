package com.doproject;

import static org.assertj.core.api.Assertions.assertThat;

import com.doproject.model.Job;
import com.doproject.model.PromptResult;
import com.doproject.repository.FileJobPersistence;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.fasterxml.jackson.databind.ObjectMapper;

class JobPersistenceTest {
    @TempDir Path directory;

    @Test
    void savesAndRestoresJobSnapshot() throws Exception {
        FileJobPersistence persistence = new FileJobPersistence(new ObjectMapper(),
                new PersistenceProperties("file", directory, "", "", "", "", ""));
        Job job = new Job("job-1", 1, 2, "premium");
        job.record(PromptResult.success(0, "prompt", "answer"));
        job.finish();

        persistence.save(job.snapshot());

        assertThat(Files.exists(directory.resolve("job-1.json"))).isTrue();
        assertThat(persistence.find("job-1")).isPresent()
                .get().extracting(snapshot -> snapshot.status()).isEqualTo(com.doproject.model.JobStatus.COMPLETED);
    }
}

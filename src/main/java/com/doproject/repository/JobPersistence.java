package com.doproject.repository;

import com.doproject.model.JobSnapshot;
import java.io.IOException;
import java.util.Optional;

public interface JobPersistence {
    void save(JobSnapshot snapshot) throws IOException;
    Optional<JobSnapshot> find(String jobId) throws IOException;
}

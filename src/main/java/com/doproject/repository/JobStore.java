package com.doproject.repository;

import com.doproject.model.Job;
import com.doproject.model.JobSnapshot;
import com.doproject.model.JobStatus;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class JobStore {
    private static final Logger log = LoggerFactory.getLogger(JobStore.class);
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();
    private final JobPersistence persistence;

    public JobStore(JobPersistence persistence) {
        this.persistence = persistence;
    }

    @PostConstruct
    public void recoverInterruptedJobs() {
        try {
            for (JobSnapshot snapshot : persistence.list()) {
                if (snapshot.status() != JobStatus.QUEUED && snapshot.status() != JobStatus.RUNNING) {
                    continue;
                }
                log.warn("Marking interrupted job {} as FAILED (was {})", snapshot.id(), snapshot.status());
                Job job = Job.restore(snapshot);
                job.fail();
                save(job);
            }
        } catch (IOException exception) {
            throw new PersistenceException(exception);
        }
    }

    public Optional<Job> find(String id) {
        Job inMemory = jobs.get(id);
        if (inMemory != null) return Optional.of(inMemory);
        try {
            return persistence.find(id).map(Job::restore);
        } catch (IOException exception) {
            log.error("Failed to load job {}", id, exception);
            throw new PersistenceException(exception);
        }
    }

    public Job create(int promptCount, int maxJobs, String routeName) {
        synchronized (this) {
            if (jobs.size() >= maxJobs) throw new TooManyJobsException();
            String id = UUID.randomUUID().toString();
            Job job = new Job(id, promptCount, routeName);
            jobs.put(id, job);
            save(job);
            return job;
        }
    }

    public void save(Job job) {
        try {
            persistence.save(job.snapshot());
        } catch (IOException exception) {
            log.error("Failed to persist job {}", job.id(), exception);
            throw new PersistenceException(exception);
        }
    }

    public void remove(String id) { jobs.remove(id); }

    public static class TooManyJobsException extends RuntimeException { }
    public static class PersistenceException extends RuntimeException {
        public PersistenceException(Throwable cause) { super(cause); }
    }
}

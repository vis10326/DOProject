package com.doproject.repository;

import com.doproject.model.Job;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class JobStore {
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();
    private final JobPersistence persistence;

    public JobStore(JobPersistence persistence) {
        this.persistence = persistence;
    }

    public Optional<Job> find(String id) {
        Job inMemory = jobs.get(id);
        if (inMemory != null) return Optional.of(inMemory);
        try {
            return persistence.find(id).map(snapshot -> {
                Job restored = Job.restore(snapshot, 1);
                jobs.put(id, restored);
                return restored;
            });
        } catch (IOException exception) {
            throw new PersistenceException(exception);
        }
    }

    public Job create(int promptCount, int maxJobs, int queueCapacity, String routeName) {
        if (jobs.size() >= maxJobs) throw new TooManyJobsException();
        String id = UUID.randomUUID().toString();
        Job job = new Job(id, promptCount, queueCapacity, routeName);
        jobs.put(id, job);
        save(job);
        return job;
    }

    public void save(Job job) {
        try {
            persistence.save(job.snapshot());
        } catch (IOException exception) {
            throw new PersistenceException(exception);
        }
    }

    public void remove(String id) { jobs.remove(id); }

    public static class TooManyJobsException extends RuntimeException { }
    public static class PersistenceException extends RuntimeException {
        public PersistenceException(Throwable cause) { super(cause); }
    }
}

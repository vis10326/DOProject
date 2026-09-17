package com.doproject.repository;

import com.doproject.model.Job;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class JobStore {
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();

    public Optional<Job> find(String id) { return Optional.ofNullable(jobs.get(id)); }

    public Job create(int promptCount, int maxJobs) {
        if (jobs.size() >= maxJobs) throw new TooManyJobsException();
        String id = UUID.randomUUID().toString();
        Job job = new Job(id, promptCount);
        jobs.put(id, job);
        return job;
    }

    public void remove(String id) { jobs.remove(id); }

    public static class TooManyJobsException extends RuntimeException { }
}

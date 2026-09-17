package com.doproject.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class Job {
    private final String id;
    private final String routeName;
    private volatile int total;
    private final Instant createdAt;
    private final AtomicInteger completed = new AtomicInteger();
    private final AtomicInteger succeeded = new AtomicInteger();
    private final AtomicInteger failed = new AtomicInteger();
    private final List<PromptResult> results = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger pendingWork = new AtomicInteger(1);
    private final AtomicReference<JobStatus> status = new AtomicReference<>(JobStatus.QUEUED);

    public Job(String id, int total, String routeName) {
        this(id, total, routeName, Instant.now());
    }

    private Job(String id, int total, String routeName, Instant createdAt) {
        this.id = id;
        this.routeName = routeName;
        this.total = total;
        this.createdAt = createdAt == null ? Instant.now() : createdAt;
    }

    public boolean start() {
        return status.compareAndSet(JobStatus.QUEUED, JobStatus.RUNNING);
    }

    public void setTotal(int total) { this.total = total; }

    public void record(PromptResult result) {
        results.add(result);
        completed.incrementAndGet();
        if (result.successful()) succeeded.incrementAndGet(); else failed.incrementAndGet();
    }

    public boolean finish() {
        JobStatus terminal = failed.get() == 0 ? JobStatus.COMPLETED : JobStatus.COMPLETED_WITH_ERRORS;
        return status.compareAndSet(JobStatus.RUNNING, terminal);
    }

    public boolean fail() {
        while (true) {
            JobStatus current = status.get();
            if (current == JobStatus.FAILED) return true;
            if (current == JobStatus.COMPLETED || current == JobStatus.COMPLETED_WITH_ERRORS) return false;
            if (status.compareAndSet(current, JobStatus.FAILED)) return true;
        }
    }

    public int incrementPending() {
        return pendingWork.incrementAndGet();
    }

    public int decrementPending() {
        return pendingWork.decrementAndGet();
    }

    public String id() { return id; }
    public String routeName() { return routeName; }
    public int total() { return total; }
    public Instant createdAt() { return createdAt; }
    public JobStatus status() { return status.get(); }
    public int completed() { return completed.get(); }
    public int succeeded() { return succeeded.get(); }
    public int failed() { return failed.get(); }

    public JobSnapshot snapshot() {
        return new JobSnapshot(id, routeName, total, completed(), succeeded(), failed(), status(),
                createdAt, orderedResults());
    }

    public static Job restore(JobSnapshot snapshot) {
        Job job = new Job(snapshot.id(), snapshot.total(), snapshot.routeName(), snapshot.createdAt());
        job.status.set(snapshot.status());
        job.results.addAll(snapshot.results() == null ? List.of() : snapshot.results());
        job.completed.set(snapshot.completed());
        job.succeeded.set(snapshot.succeeded());
        job.failed.set(snapshot.failed());
        job.pendingWork.set(0);
        return job;
    }

    public List<PromptResult> orderedResults() {
        List<PromptResult> copy;
        synchronized (results) {
            copy = new ArrayList<>(results);
        }
        copy.sort(Comparator.comparingInt(PromptResult::index));
        return copy;
    }
}

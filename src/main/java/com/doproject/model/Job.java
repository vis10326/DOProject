package com.doproject.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

public final class Job {
    private final String id;
    private volatile String routeName;
    private volatile int total;
    private final Instant createdAt = Instant.now();
    private final AtomicInteger completed = new AtomicInteger();
    private final AtomicInteger succeeded = new AtomicInteger();
    private final AtomicInteger failed = new AtomicInteger();
    private final List<PromptResult> results = new CopyOnWriteArrayList<>();
    private final BlockingQueue<PromptChunk> promptQueue;
    private volatile JobStatus status = JobStatus.QUEUED;

    public Job(String id, int total, int queueCapacity, String routeName) {
        this.id = id;
        this.routeName = routeName;
        this.total = total;
        this.promptQueue = new ArrayBlockingQueue<>(queueCapacity);
    }

    public void start() { status = JobStatus.RUNNING; }

    public void setTotal(int total) { this.total = total; }

    public void record(PromptResult result) {
        results.add(result);
        completed.incrementAndGet();
        if (result.successful()) succeeded.incrementAndGet(); else failed.incrementAndGet();
    }

    public void finish() {
        status = failed.get() == 0 ? JobStatus.COMPLETED : JobStatus.COMPLETED_WITH_ERRORS;
    }

    public void fail() { status = JobStatus.FAILED; }

    public String id() { return id; }
    public String routeName() { return routeName; }
    public void setRouteName(String routeName) { this.routeName = routeName; }
    public int total() { return total; }
    public Instant createdAt() { return createdAt; }
    public JobStatus status() { return status; }
    public int completed() { return completed.get(); }
    public int succeeded() { return succeeded.get(); }
    public int failed() { return failed.get(); }
    public BlockingQueue<PromptChunk> promptQueue() { return promptQueue; }

    public JobSnapshot snapshot() {
        return new JobSnapshot(id, routeName, total, completed(), succeeded(), failed(), status,
                createdAt, orderedResults());
    }

    public static Job restore(JobSnapshot snapshot, int queueCapacity) {
        Job job = new Job(snapshot.id(), snapshot.total(), queueCapacity, snapshot.routeName());
        job.status = snapshot.status();
        job.results.addAll(snapshot.results());
        job.completed.set(snapshot.completed());
        job.succeeded.set(snapshot.succeeded());
        job.failed.set(snapshot.failed());
        return job;
    }

    public List<PromptResult> orderedResults() {
        List<PromptResult> copy = new ArrayList<>(results);
        copy.sort(Comparator.comparingInt(PromptResult::index));
        return copy;
    }
}

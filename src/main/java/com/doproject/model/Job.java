package com.doproject.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

public final class Job {
    private final String id;
    private final int total;
    private final Instant createdAt = Instant.now();
    private final AtomicInteger completed = new AtomicInteger();
    private final AtomicInteger succeeded = new AtomicInteger();
    private final AtomicInteger failed = new AtomicInteger();
    private final List<PromptResult> results = new CopyOnWriteArrayList<>();
    private volatile JobStatus status = JobStatus.QUEUED;

    public Job(String id, int total) {
        this.id = id;
        this.total = total;
    }

    public void start() { status = JobStatus.RUNNING; }

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
    public int total() { return total; }
    public Instant createdAt() { return createdAt; }
    public JobStatus status() { return status; }
    public int completed() { return completed.get(); }
    public int succeeded() { return succeeded.get(); }
    public int failed() { return failed.get(); }

    public List<PromptResult> orderedResults() {
        List<PromptResult> copy = new ArrayList<>(results);
        copy.sort(Comparator.comparingInt(PromptResult::index));
        return copy;
    }
}

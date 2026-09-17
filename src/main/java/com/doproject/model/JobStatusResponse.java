package com.doproject.model;

public record JobStatusResponse(String jobId, JobStatus status, int total, int completed, int succeeded, int failed) {
    public static JobStatusResponse from(Job job) {
        return new JobStatusResponse(job.id(), job.status(), job.total(), job.completed(), job.succeeded(), job.failed());
    }
}

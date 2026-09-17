package com.doproject.model;

import java.time.Instant;
import java.util.List;

public record JobSnapshot(String id, String routeName, int total, int completed, int succeeded,
                          int failed, JobStatus status, Instant createdAt, List<PromptResult> results) {
}

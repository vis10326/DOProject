package com.doproject.controller;

import com.doproject.model.Job;
import com.doproject.model.JobStatus;
import com.doproject.model.JobStatusResponse;
import com.doproject.model.PromptResult;
import com.doproject.model.WebhookRequest;
import com.doproject.repository.JobStore;
import com.doproject.repository.WebhookStore;
import com.doproject.service.BatchService;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/job")
public class BatchController {
    private final BatchService service;
    private final JobStore store;
    private final WebhookStore webhookStore;

    public BatchController(BatchService service, JobStore store, WebhookStore webhookStore) {
        this.service = service;
        this.store = store;
        this.webhookStore = webhookStore;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, String>> submit(@RequestParam("file") MultipartFile file,
                                                       @RequestParam(value = "route", required = false) String routeName)
            throws IOException {
        if (file.isEmpty()) throw new BatchService.InvalidBatchException("Batch file must not be empty");
        Job job = service.submit(file, routeName);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("jobId", job.id(), "status", job.status().name()));
    }

    @PostMapping("/local")
    public ResponseEntity<Map<String, String>> submitLocal(@RequestParam("file") String fileName,
                                                           @RequestParam(value = "route", required = false) String routeName)
            throws IOException {
        Job job = service.submitLocal(fileName, routeName);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("jobId", job.id(), "status", job.status().name()));
    }

    @GetMapping("/{id}/status")
    public JobStatusResponse status(@PathVariable String id) {
        return store.find(id).map(JobStatusResponse::from).orElseThrow(JobNotFoundException::new);
    }

    @GetMapping(value = "/{id}/download", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<PromptResult>> download(@PathVariable String id) {
        Job job = store.find(id).orElseThrow(JobNotFoundException::new);
        if (job.status() != JobStatus.COMPLETED && job.status() != JobStatus.COMPLETED_WITH_ERRORS) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
        return ResponseEntity.ok(job.orderedResults());
    }

    @PostMapping("/{id}/webhook")
    public WebhookRequest registerWebhook(@PathVariable String id, @RequestBody WebhookRequest request) {
        store.find(id).orElseThrow(JobNotFoundException::new);
        if (request.callbackUrl() == null || request.callbackUrl().isBlank()) {
            throw new WebhookStore.InvalidWebhookException();
        }
        webhookStore.register(id, URI.create(request.callbackUrl()));
        return request;
    }

    public static class JobNotFoundException extends RuntimeException { }
}

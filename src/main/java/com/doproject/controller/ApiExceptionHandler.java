package com.doproject.controller;

import com.doproject.repository.JobStore;
import com.doproject.service.BatchService;
import java.io.IOException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(IOException.class)
    ResponseEntity<Map<String, String>> unreadableBatch() {
        return ResponseEntity.badRequest().body(Map.of("error", "Batch file must contain a JSON prompt array"));
    }

    @ExceptionHandler(BatchService.InvalidBatchException.class)
    ResponseEntity<Map<String, String>> invalid(BatchService.InvalidBatchException exception) {
        return ResponseEntity.badRequest().body(Map.of("error", exception.getMessage()));
    }

    @ExceptionHandler(BatchService.QueueFullException.class)
    ResponseEntity<Map<String, String>> queueFull() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error", "Batch queue is full"));
    }

    @ExceptionHandler(JobStore.TooManyJobsException.class)
    ResponseEntity<Map<String, String>> tooManyJobs() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error", "Job capacity reached"));
    }

    @ExceptionHandler(BatchController.JobNotFoundException.class)
    ResponseEntity<Map<String, String>> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Job not found"));
    }
}

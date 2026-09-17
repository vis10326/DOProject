package com.doproject.repository;

import com.doproject.PersistenceProperties;
import com.doproject.model.JobSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "persistence.type", havingValue = "file", matchIfMissing = true)
public class FileJobPersistence implements JobPersistence {
    private final ObjectMapper objectMapper;
    private final Path directory;

    public FileJobPersistence(ObjectMapper objectMapper, PersistenceProperties properties) {
        this.objectMapper = objectMapper;
        this.directory = properties.directory().toAbsolutePath().normalize();
    }

    @Override
    public void save(JobSnapshot snapshot) throws IOException {
        Files.createDirectories(directory);
        Path target = directory.resolve(snapshot.id() + ".json").normalize();
        if (!target.startsWith(directory)) throw new IOException("Invalid persistence key");
        Path temporary = Files.createTempFile(directory, snapshot.id(), ".tmp");
        try {
            objectMapper.writeValue(temporary.toFile(), snapshot);
            Files.move(temporary, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    @Override
    public Optional<JobSnapshot> find(String jobId) throws IOException {
        Path file = directory.resolve(jobId + ".json").normalize();
        if (!file.startsWith(directory) || !Files.exists(file)) return Optional.empty();
        return Optional.of(objectMapper.readValue(file.toFile(), JobSnapshot.class));
    }
}

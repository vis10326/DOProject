package com.doproject.repository;

import com.doproject.PersistenceProperties;
import com.doproject.model.JobSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

@Component
@ConditionalOnProperty(name = "persistence.type", havingValue = "spaces")
public class SpacesJobPersistence implements JobPersistence {
    private final ObjectMapper objectMapper;
    private final PersistenceProperties properties;
    private final S3Client client;

    public SpacesJobPersistence(ObjectMapper objectMapper, PersistenceProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.client = S3Client.builder()
                .endpointOverride(URI.create(properties.endpoint()))
                .region(Region.of(properties.region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(properties.accessKey(), properties.secretKey())))
                .build();
    }

    @Override
    public void save(JobSnapshot snapshot) throws IOException {
        byte[] payload = objectMapper.writeValueAsBytes(snapshot);
        client.putObject(PutObjectRequest.builder().bucket(properties.bucket()).key(key(snapshot.id()))
                        .contentType("application/json").build(), RequestBody.fromBytes(payload));
    }

    @Override
    public Optional<JobSnapshot> find(String jobId) throws IOException {
        try {
            byte[] payload = client.getObjectAsBytes(GetObjectRequest.builder()
                    .bucket(properties.bucket()).key(key(jobId)).build()).asByteArray();
            return Optional.of(objectMapper.readValue(payload, JobSnapshot.class));
        } catch (NoSuchKeyException exception) {
            return Optional.empty();
        }
    }

    private String key(String jobId) {
        return "jobs/" + jobId + ".json";
    }
}

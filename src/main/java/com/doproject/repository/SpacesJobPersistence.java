package com.doproject.repository;

import com.doproject.PersistenceProperties;
import com.doproject.model.JobSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;

@Component
@ConditionalOnProperty(name = "persistence.type", havingValue = "spaces")
public class SpacesJobPersistence implements JobPersistence {
    private static final Logger log = LoggerFactory.getLogger(SpacesJobPersistence.class);
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
        } catch (SdkException exception) {
            throw new IOException("Failed to load job snapshot from Spaces", exception);
        }
    }

    @Override
    public List<JobSnapshot> list() throws IOException {
        try {
            List<JobSnapshot> snapshots = new ArrayList<>();
            ListObjectsV2Request request = ListObjectsV2Request.builder()
                    .bucket(properties.bucket()).prefix("jobs/").build();
            for (S3Object object : client.listObjectsV2Paginator(request).contents()) {
                String key = object.key();
                if (key == null || !key.endsWith(".json")) continue;
                String jobId = key.substring(key.lastIndexOf('/') + 1, key.length() - ".json".length());
                try {
                    find(jobId).ifPresent(snapshots::add);
                } catch (IOException exception) {
                    log.warn("Skipping unreadable Spaces job snapshot {}", key, exception);
                }
            }
            return snapshots;
        } catch (SdkException exception) {
            throw new IOException("Failed to list job snapshots from Spaces", exception);
        }
    }

    private String key(String jobId) {
        return "jobs/" + jobId + ".json";
    }
}

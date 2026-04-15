package com.medirag.diagnostic_service.service;

import io.minio.*;
import io.minio.http.Method;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class MinioService {

    private MinioClient uploadClient;      // internal — http://minio:9000
    private MinioClient presignedClient;   // external — http://host.docker.internal:9000

    @Value("${minio.endpoint}")
    private String endpoint;

    @Value("${minio.presigned-endpoint:http://host.docker.internal:9000}")
    private String presignedEndpoint;

    @Value("${minio.access-key}")
    private String accessKey;

    @Value("${minio.secret-key}")
    private String secretKey;

    @Value("${minio.scan-bucket}")
    private String scanBucket;

    @Value("${minio.report-bucket}")
    private String reportBucket;

    @Value("${minio.presigned-expiry-minutes:15}")
    private int presignedExpiryMinutes;

    @Value("${minio.public-url:http://localhost:9000}")
    private String publicUrl;

    @PostConstruct
    public void init() {
        log.info("MinioService init — endpoint={}, presignedEndpoint={}, scanBucket={}, reportBucket={}",
                endpoint, presignedEndpoint, scanBucket, reportBucket);

        // Upload client stays the same (uses internal network http://minio:9000)
        this.uploadClient = MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();

        // Presigned client (uses http://localhost:9000)
        this.presignedClient = MinioClient.builder()
                .endpoint(presignedEndpoint)
                .credentials(accessKey, secretKey)
                .region("us-east-1") // <--- ADD THIS LINE to prevent internal network lookups
                .build();

        ensureBucketWithRetry(scanBucket);
        ensureBucketWithRetry(reportBucket);
    }

    private void ensureBucketWithRetry(String bucketName) {
        int maxRetries = 5;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                boolean exists = uploadClient.bucketExists(
                    BucketExistsArgs.builder().bucket(bucketName).build()
                );
                if (!exists) {
                    uploadClient.makeBucket(
                        MakeBucketArgs.builder().bucket(bucketName).build()
                    );
                    log.info("Created MinIO bucket: {}", bucketName);
                } else {
                    log.info("MinIO bucket already exists: {}", bucketName);
                }
                return;
            } catch (Exception e) {
                log.warn("Attempt {}/{} — bucket {}: {}", attempt, maxRetries, bucketName, e.getMessage());
                if (attempt < maxRetries) {
                    try { Thread.sleep(3000L * attempt); }
                    catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                } else {
                    log.error("Could not create bucket {} after {} attempts", bucketName, maxRetries);
                }
            }
        }
    }

    public String uploadScan(MultipartFile file, Long patientId) {
        try {
            String objectName = "xrays/" + patientId + "/"
                    + UUID.randomUUID() + "-" + file.getOriginalFilename();

            uploadClient.putObject(
                PutObjectArgs.builder()
                    .bucket(scanBucket)
                    .object(objectName)
                    .stream(file.getInputStream(), file.getSize(), -1)
                    .contentType(file.getContentType())
                    .build()
            );

            log.info("Uploaded to MinIO: {}/{}", scanBucket, objectName);
            return objectName;

        } catch (Exception e) {
            log.error("MinIO upload failed: {}", e.getMessage());
            throw new RuntimeException("Failed to upload file: " + e.getMessage());
        }
    }

    public String generatePresignedUrl(String objectName) {
        try {
            // presignedClient uses host.docker.internal:9000
            // → signs with that host → browser sends Host: localhost:9000
            // → Docker maps localhost:9000 → minio:9000 → signature matches
            String presigned = presignedClient.getPresignedObjectUrl(
                GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(scanBucket)
                    .object(objectName)
                    .expiry(presignedExpiryMinutes, TimeUnit.MINUTES)
                    .build()
            );

            // REMOVE the replacement hack.
            log.info("Presigned URL generated: {}", presigned.substring(0, Math.min(presigned.length(), 80)) + "...");
            return presigned;

        } catch (Exception e) {
            log.error("Failed to generate presigned URL: {}", e.getMessage());
            throw new RuntimeException("Failed to generate access URL");
        }
    }

    public String getBase64Encoded(String objectName) {
        try (InputStream stream = uploadClient.getObject(
                GetObjectArgs.builder()
                        .bucket(scanBucket)
                        .object(objectName)
                        .build())) {
            byte[] bytes = stream.readAllBytes();
            return Base64.getEncoder().encodeToString(bytes);
        } catch (Exception e) {
            log.error("Failed to read file from MinIO: {}", e.getMessage());
            throw new RuntimeException("Failed to read file from storage");
        }
    }

    public MinioClient getUploadClient() {
        return uploadClient;
    }

    public MinioClient getPresignedClient() {
        return presignedClient;
    }

    public void deleteScan(String objectName) {
        try {
            uploadClient.removeObject(
                RemoveObjectArgs.builder()
                        .bucket(scanBucket)
                        .object(objectName)
                        .build()
            );
        } catch (Exception e) {
            log.error("Failed to delete {}: {}", objectName, e.getMessage());
        }
    }
}
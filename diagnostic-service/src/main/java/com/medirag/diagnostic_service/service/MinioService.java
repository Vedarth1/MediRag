package com.medirag.diagnostic_service.service;

import io.minio.*;
import io.minio.http.Method;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class MinioService {

    private final MinioClient minioClient;

    @Value("${minio.scan-bucket}")
    private String scanBucket;

    @Value("${minio.report-bucket}")
    private String reportBucket;

    @Value("${minio.presigned-expiry-minutes:15}")
    private int presignedExpiryMinutes;

    public MinioService(
            @Value("${minio.endpoint}") String endpoint,
            @Value("${minio.access-key}") String accessKey,
            @Value("${minio.secret-key}") String secretKey) {

        this.minioClient = MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();

        // Ensure buckets exist on startup
        ensureBucketExists(scanBucket);
        ensureBucketExists(reportBucket);
    }

    // ── Upload a file to MinIO ──────────────────────────────────────────────

    public String uploadScan(MultipartFile file, Long patientId) {
        try {
            // Build a unique object path inside the bucket
            String extension = getExtension(file.getOriginalFilename());
            String objectName = "xrays/" + patientId + "/"
                    + UUID.randomUUID() + "-" + file.getOriginalFilename();

            minioClient.putObject(
                PutObjectArgs.builder()
                    .bucket(scanBucket)
                    .object(objectName)
                    .stream(file.getInputStream(), file.getSize(), -1)
                    .contentType(file.getContentType())
                    .build()
            );

            log.info("Uploaded scan: {}/{}", scanBucket, objectName);
            return objectName;   // return the path, not a full URL

        } catch (Exception e) {
            log.error("MinIO upload failed: {}", e.getMessage());
            throw new RuntimeException("Failed to upload file to storage: " + e.getMessage());
        }
    }

    // ── Generate a presigned URL for temporary access ───────────────────────

    public String generatePresignedUrl(String objectName) {
        try {
            return minioClient.getPresignedObjectUrl(
                GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(scanBucket)
                    .object(objectName)
                    .expiry(presignedExpiryMinutes, TimeUnit.MINUTES)
                    .build()
            );
        } catch (Exception e) {
            log.error("Failed to generate presigned URL: {}", e.getMessage());
            throw new RuntimeException("Failed to generate access URL");
        }
    }

    // ── Get file as stream (for sending to AI) ──────────────────────────────

    public InputStream getFileStream(String objectName) {
        try {
            return minioClient.getObject(
                GetObjectArgs.builder()
                    .bucket(scanBucket)
                    .object(objectName)
                    .build()
            );
        } catch (Exception e) {
            throw new RuntimeException("Failed to retrieve file from storage");
        }
    }

    // ── Delete a file ───────────────────────────────────────────────────────

    public void deleteScan(String objectName) {
        try {
            minioClient.removeObject(
                RemoveObjectArgs.builder()
                    .bucket(scanBucket)
                    .object(objectName)
                    .build()
            );
        } catch (Exception e) {
            log.error("Failed to delete object: {}", e.getMessage());
        }
    }

    // ── Ensure bucket exists — create if not ───────────────────────────────

    private void ensureBucketExists(String bucketName) {
        try {
            boolean exists = minioClient.bucketExists(
                BucketExistsArgs.builder().bucket(bucketName).build()
            );
            if (!exists) {
                minioClient.makeBucket(
                    MakeBucketArgs.builder().bucket(bucketName).build()
                );
                log.info("Created MinIO bucket: {}", bucketName);
            }
        } catch (Exception e) {
            log.error("Failed to ensure bucket {}: {}", bucketName, e.getMessage());
        }
    }

    private String getExtension(String filename) {
        if (filename == null || !filename.contains(".")) return "jpg";
        return filename.substring(filename.lastIndexOf('.') + 1);
    }
}
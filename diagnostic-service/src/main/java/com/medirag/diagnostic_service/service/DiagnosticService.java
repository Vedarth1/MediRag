package com.medirag.diagnostic_service.service;

import com.medirag.diagnostic_service.dto.*;
import com.medirag.diagnostic_service.entity.*;
import com.medirag.diagnostic_service.repository.*;
import com.medirag.diagnostic_service.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class DiagnosticService {

    private final ScanUploadRepository scanRepository;
    private final DiagnosticReportRepository reportRepository;
    private final MinioService minioService;
    private final AIAnalysisService aiAnalysisService;
    private final JwtUtil jwtUtil;

    // Allowed file types — security: reject non-medical files
    private static final List<String> ALLOWED_TYPES = List.of(
        "image/jpeg", "image/png", "image/webp", "application/pdf"
    );

    // ── Upload and trigger analysis ─────────────────────────────────────────

    @Transactional
    public ScanUploadResponse uploadScan(MultipartFile file,
                                         String scanType,
                                         String authHeader) {
        Long patientId = extractUserId(authHeader);
        String patientEmail = extractEmail(authHeader);

        // Validate file type
        String contentType = file.getContentType();
        if (!ALLOWED_TYPES.contains(contentType)) {
            throw new RuntimeException(
                "Invalid file type: " + contentType +
                ". Allowed: JPEG, PNG, WebP, PDF");
        }

        // Validate file size (max 20MB enforced here too)
        if (file.getSize() > 20 * 1024 * 1024) {
            throw new RuntimeException("File too large. Maximum size is 20MB.");
        }

        // 1. Upload file to MinIO, get the object path back
        String objectName = minioService.uploadScan(file, patientId);

        // 2. Save scan record to PostgreSQL
        ScanUpload scan = ScanUpload.builder()
                .patientId(patientId)
                .patientEmail(patientEmail)
                .fileName(file.getOriginalFilename())
                .fileUrl(objectName)
                .contentType(contentType)
                .fileSizeBytes(file.getSize())
                .scanType(parseScanType(scanType))
                .status(ScanUpload.ScanStatus.UPLOADED)
                .build();

        ScanUpload saved = scanRepository.save(scan);

        // 3. Trigger AI analysis asynchronously
        // The HTTP response returns immediately with UPLOADED status
        triggerAnalysis(saved.getId());

        return toScanResponse(saved);
    }

    // ── Async AI analysis ───────────────────────────────────────────────────

    @Async
    @Transactional
    public void triggerAnalysis(Long scanId) {
        ScanUpload scan = scanRepository.findById(scanId).orElse(null);
        if (scan == null) return;

        try {
            // Mark as analysing
            scan.setStatus(ScanUpload.ScanStatus.ANALYSING);
            scanRepository.save(scan);

            // Run AI analysis
            DiagnosticReport report = aiAnalysisService.analyseXray(scan);

            // Save report
            reportRepository.save(report);

            // Mark scan as complete
            scan.setStatus(ScanUpload.ScanStatus.COMPLETED);
            scan.setReport(report);
            scanRepository.save(scan);

            log.info("Analysis completed for scan {}", scanId);

        } catch (Exception e) {
            log.error("Analysis failed for scan {}: {}", scanId, e.getMessage());
            scan.setStatus(ScanUpload.ScanStatus.FAILED);
            scanRepository.save(scan);
        }
    }

    // ── Get patient's scan history ──────────────────────────────────────────

    public List<ScanUploadResponse> getMyScan(String authHeader) {
        Long patientId = extractUserId(authHeader);
        return scanRepository.findByPatientIdOrderByUploadedAtDesc(patientId)
                .stream()
                .map(this::toScanResponse)
                .collect(Collectors.toList());
    }

    // ── Get full diagnostic report ──────────────────────────────────────────

    public DiagnosticReportResponse getReport(Long scanId, String authHeader) {
        Long patientId = extractUserId(authHeader);

        // Ownership check
        ScanUpload scan = scanRepository.findByIdAndPatientId(scanId, patientId)
                .orElseThrow(() -> new RuntimeException("Scan not found"));

        if (scan.getStatus() != ScanUpload.ScanStatus.COMPLETED) {
            throw new RuntimeException(
                "Report not ready yet. Current status: " + scan.getStatus());
        }

        DiagnosticReport report = reportRepository.findByScanId(scanId)
                .orElseThrow(() -> new RuntimeException("Report not found"));

        return toReportResponse(report, scan);
    }

    // ── Get presigned URL to view the scan image ────────────────────────────

    public PresignedUrlResponse getPresignedUrl(Long scanId, String authHeader) {
        Long patientId = extractUserId(authHeader);

        ScanUpload scan = scanRepository.findByIdAndPatientId(scanId, patientId)
                .orElseThrow(() -> new RuntimeException("Scan not found"));

        String url = minioService.generatePresignedUrl(scan.getFileUrl());

        return PresignedUrlResponse.builder()
                .url(url)
                .expiryMinutes(15)
                .fileName(scan.getFileName())
                .build();
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private Long extractUserId(String authHeader) {
        return jwtUtil.extractUserId(authHeader.replace("Bearer ", ""));
    }

    private String extractEmail(String authHeader) {
        return jwtUtil.extractEmail(authHeader.replace("Bearer ", ""));
    }

    private ScanUpload.ScanType parseScanType(String type) {
        try {
            return ScanUpload.ScanType.valueOf(type.toUpperCase());
        } catch (Exception e) {
            return ScanUpload.ScanType.XRAY;
        }
    }

    private ScanUploadResponse toScanResponse(ScanUpload s) {
        return ScanUploadResponse.builder()
                .id(s.getId())
                .fileName(s.getFileName())
                .scanType(s.getScanType().name())
                .status(s.getStatus().name())
                .uploadedAt(s.getUploadedAt())
                .reportReady(s.getStatus() == ScanUpload.ScanStatus.COMPLETED)
                .build();
    }

    private DiagnosticReportResponse toReportResponse(DiagnosticReport r, ScanUpload scan) {
        List<FindingResponse> findings = r.getFindings().stream()
                .map(f -> FindingResponse.builder()
                        .id(f.getId())
                        .condition(f.getCondition())
                        .confidence(f.getConfidence())
                        .severity(f.getSeverity() != null ? f.getSeverity().name() : null)
                        .location(f.getLocation())
                        .boundingBox(f.getBoundingBox())
                        .build())
                .collect(Collectors.toList());

        return DiagnosticReportResponse.builder()
                .id(r.getId())
                .scanId(scan.getId())
                .fileName(scan.getFileName())
                .summary(r.getSummary())
                .overallConfidence(r.getOverallConfidence())
                .findings(findings)
                .reportPdfUrl(r.getReportPdfUrl())
                .generatedAt(r.getGeneratedAt())
                .build();
    }
}
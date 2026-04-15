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
    private final PdfReportService pdfReportService;

    private static final List<String> ALLOWED_TYPES = List.of(
        "image/jpeg", "image/png", "image/webp", "application/pdf"
    );

    @Transactional
    public ScanUploadResponse uploadScan(MultipartFile file,
                                          String scanType,
                                          String authHeader) {
        Long patientId = extractUserId(authHeader);
        String patientEmail = extractEmail(authHeader);

        String contentType = file.getContentType();
        if (!ALLOWED_TYPES.contains(contentType)) {
            throw new RuntimeException("Invalid file type: " + contentType);
        }
        if (file.getSize() > 20 * 1024 * 1024) {
            throw new RuntimeException("File too large. Maximum size is 20MB.");
        }

        String objectName = minioService.uploadScan(file, patientId);

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
        triggerAnalysis(saved.getId());
        return toScanResponse(saved);
    }

    @Async
    @Transactional
    public void triggerAnalysis(Long scanId) {
        ScanUpload scan = scanRepository.findById(scanId).orElse(null);
        if (scan == null) return;

        try {
            scan.setStatus(ScanUpload.ScanStatus.ANALYSING);
            scanRepository.save(scan);

            DiagnosticReport report = aiAnalysisService.analyseXray(scan);
            DiagnosticReport saved = reportRepository.save(report);
            log.info("Report saved with id={}", saved.getId());

            log.info("Calling pdfReportService.generateAndUpload...");
            String pdfUrl = pdfReportService.generateAndUpload(saved, scan);
            log.info("PDF URL returned: {}", pdfUrl);

            if (pdfUrl != null) {
                saved.setReportPdfUrl(pdfUrl);
                reportRepository.save(saved);
                log.info("Report updated with PDF URL");
            } else {
                log.warn("PDF URL was null — skipping update");
            }

            scan.setStatus(ScanUpload.ScanStatus.COMPLETED);
            scan.setReport(saved);
            scanRepository.save(scan);
            log.info("Analysis complete for scan {}", scanId);

        } catch (Exception e) {
            log.error("Analysis failed for scan {}: {}", scanId, e.getMessage(), e);
            scan.setStatus(ScanUpload.ScanStatus.FAILED);
            scanRepository.save(scan);
        }
    }

    public List<ScanUploadResponse> getMyScan(String authHeader) {
        Long patientId = extractUserId(authHeader);
        return scanRepository.findByPatientIdOrderByUploadedAtDesc(patientId)
                .stream().map(this::toScanResponse).collect(Collectors.toList());
    }

    public DiagnosticReportResponse getReport(Long scanId, String authHeader) {
        Long patientId = extractUserId(authHeader);

        ScanUpload scan = scanRepository.findByIdAndPatientId(scanId, patientId)
                .orElseThrow(() -> new RuntimeException("Scan not found"));

        if (scan.getStatus() != ScanUpload.ScanStatus.COMPLETED) {
            throw new RuntimeException("Report not ready yet. Status: " + scan.getStatus());
        }

        DiagnosticReport report = reportRepository.findByScanId(scanId)
                .orElseThrow(() -> new RuntimeException("Report not found"));

        return toReportResponse(report, scan);
    }

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

    private Long extractUserId(String authHeader) {
        return jwtUtil.extractUserId(authHeader.replace("Bearer ", ""));
    }

    private String extractEmail(String authHeader) {
        return jwtUtil.extractEmail(authHeader.replace("Bearer ", ""));
    }

    private ScanUpload.ScanType parseScanType(String type) {
        try { return ScanUpload.ScanType.valueOf(type.toUpperCase()); }
        catch (Exception e) { return ScanUpload.ScanType.XRAY; }
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
                .reportPdfUrl(r.getReportPdfUrl())  // already has localhost URL
                .generatedAt(r.getGeneratedAt())
                .build();
    }
}
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
    private final ReportTextExtractionService reportTextExtractionService;
    private final JwtUtil jwtUtil;
    private final PdfReportService pdfReportService;
    private final DocumentChunkingService chunkingService;

    private static final List<String> ALLOWED_TYPES = List.of(
        "image/jpeg", "image/png", "image/webp",
        "application/pdf",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "text/plain"
    );

    // ── Upload (entry point — same for both pipelines) ────────────────────

    @Transactional
    public ScanUploadResponse uploadScan(MultipartFile file,
                                          String scanType,
                                          String authHeader) {
        Long patientId   = extractUserId(authHeader);
        String patientEmail = extractEmail(authHeader);
        String contentType  = file.getContentType();

        if (!ALLOWED_TYPES.contains(contentType)) {
            throw new RuntimeException("Invalid file type: " + contentType +
                    ". Allowed: JPEG, PNG, WebP, PDF, DOCX, TXT");
        }
        if (file.getSize() > 20 * 1024 * 1024) {
            throw new RuntimeException("File too large. Maximum 20MB.");
        }

        boolean isReport = reportTextExtractionService.isReportFile(contentType);
        String pipeline  = isReport ? "REPORT" : "IMAGE";

        String objectName = minioService.uploadScan(file, patientId);

        ScanUpload scan = ScanUpload.builder()
                .patientId(patientId)
                .patientEmail(patientEmail)
                .fileName(file.getOriginalFilename())
                .fileUrl(objectName)
                .contentType(contentType)
                .fileSizeBytes(file.getSize())
                .scanType(parseScanType(scanType))
                .pipeline(pipeline)
                .status(ScanUpload.ScanStatus.UPLOADED)
                .build();

        ScanUpload saved = scanRepository.save(scan);
        log.info("Scan {} saved — pipeline={}, file={}", saved.getId(), pipeline, saved.getFileName());

        triggerAnalysis(saved.getId());
        return toScanResponse(saved);
    }

    // ── Async analysis — branches by pipeline ─────────────────────────────

    @Async
    @Transactional
    public void triggerAnalysis(Long scanId) {
        ScanUpload scan = scanRepository.findById(scanId).orElse(null);
        if (scan == null) {
            log.error("triggerAnalysis: scan {} not found", scanId);
            return;
        }

        try {
            scan.setStatus(ScanUpload.ScanStatus.ANALYSING);
            scanRepository.save(scan);

            DiagnosticReport report;

            if ("REPORT".equals(scan.getPipeline())) {
                // ── REPORT PIPELINE ─────────────────────────────────────
                log.info("Report pipeline starting for scan {}", scanId);
                report = runReportPipeline(scan);
            } else {
                // ── IMAGE PIPELINE ──────────────────────────────────────
                log.info("Image pipeline starting for scan {}", scanId);
                report = aiAnalysisService.analyseXray(scan);
            }

            // ── Save report + PDF (same for both pipelines) ──────────────
            DiagnosticReport saved = reportRepository.save(report);
            log.info("Report saved with id={} for scan {}", saved.getId(), scanId);

            String pdfUrl = pdfReportService.generateAndUpload(saved, scan);
            if (pdfUrl != null) {
                saved.setReportPdfUrl(pdfUrl);
                reportRepository.save(saved);
                log.info("PDF URL saved for scan {}", scanId);
            } else {
                log.warn("PDF generation returned null for scan {}", scanId);
            }

            scan.setStatus(ScanUpload.ScanStatus.COMPLETED);
            scan.setReport(saved);
            scanRepository.save(scan);
            log.info("Analysis COMPLETED for scan {} via {} pipeline",
                    scanId, scan.getPipeline());

        } catch (Exception e) {
            log.error("Analysis FAILED for scan {}: {}", scanId, e.getMessage(), e);
            scan.setStatus(ScanUpload.ScanStatus.FAILED);
            scanRepository.save(scan);
        }
    }

    /**
     * Report pipeline steps — called from triggerAnalysis().
     * Separated to keep triggerAnalysis() readable.
     */
    private DiagnosticReport runReportPipeline(ScanUpload scan) {
        // Extract text from the uploaded file stored in MinIO
        try (java.io.InputStream stream = minioService.getFileStream(scan.getFileUrl())) {

            // Extract text once, then chunk (avoids re-parsing PDF/DOCX twice)
            // Uses InputStream-based extraction — no MockMultipartFile dependency
            String fullText = reportTextExtractionService.extractText(
                    stream, scan.getContentType(), scan.getFileName(), scan.getFileSizeBytes());
            List<String> chunks = chunkingService.chunk(fullText);

            log.info("Report pipeline: {} chars extracted, {} chunks for scan {}",
                    fullText.length(), chunks.size(), scan.getId());

            // Delegate to AIAnalysisService report pipeline
            return aiAnalysisService.analyseReport(scan, chunks, fullText);

        } catch (Exception e) {
            log.error("Report pipeline extraction failed for scan {}: {}",
                    scan.getId(), e.getMessage(), e);
            throw new RuntimeException("Report text extraction failed: " + e.getMessage());
        }
    }

    // ── Existing read endpoints — unchanged ───────────────────────────────

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
            throw new RuntimeException("Report not ready. Status: " + scan.getStatus());
        }

        DiagnosticReport report = reportRepository.findByScanId(scanId)
                .orElseThrow(() -> new RuntimeException("Report not found"));

        return toReportResponse(report, scan);
    }

    public PresignedUrlResponse getPresignedUrl(Long scanId, String authHeader) {
        Long patientId = extractUserId(authHeader);

        ScanUpload scan = scanRepository.findByIdAndPatientId(scanId, patientId)
                .orElseThrow(() -> new RuntimeException("Scan not found"));

        return PresignedUrlResponse.builder()
                .url(minioService.generatePresignedUrl(scan.getFileUrl()))
                .expiryMinutes(15)
                .fileName(scan.getFileName())
                .build();
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private Long extractUserId(String h) {
        return jwtUtil.extractUserId(h.replace("Bearer ", ""));
    }

    private String extractEmail(String h) {
        return jwtUtil.extractEmail(h.replace("Bearer ", ""));
    }

    private ScanUpload.ScanType parseScanType(String type) {
        try   { return ScanUpload.ScanType.valueOf(type.toUpperCase()); }
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
                .reportPdfUrl(r.getReportPdfUrl())
                .generatedAt(r.getGeneratedAt())
                .build();
    }
}
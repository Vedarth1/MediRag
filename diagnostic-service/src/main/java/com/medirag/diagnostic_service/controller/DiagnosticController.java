package com.medirag.diagnostic_service.controller;

import com.medirag.diagnostic_service.dto.*;
import com.medirag.diagnostic_service.service.DiagnosticService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/diagnostics")
@RequiredArgsConstructor
public class DiagnosticController {

    private final DiagnosticService diagnosticService;

    // POST /api/diagnostics/upload — multipart form data
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ScanUploadResponse> uploadScan(
            @RequestPart("file") MultipartFile file,
            @RequestParam(defaultValue = "XRAY") String scanType,
            @RequestHeader("Authorization") String authHeader) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(diagnosticService.uploadScan(file, scanType, authHeader));
    }

    // GET /api/diagnostics/my
    @GetMapping("/my")
    public ResponseEntity<List<ScanUploadResponse>> myScans(
            @RequestHeader("Authorization") String authHeader) {
        return ResponseEntity.ok(diagnosticService.getMyScan(authHeader));
    }

    // GET /api/diagnostics/{scanId}/report
    @GetMapping("/{scanId}/report")
    public ResponseEntity<DiagnosticReportResponse> getReport(
            @PathVariable Long scanId,
            @RequestHeader("Authorization") String authHeader) {
        return ResponseEntity.ok(diagnosticService.getReport(scanId, authHeader));
    }

    // GET /api/diagnostics/{scanId}/view
    @GetMapping("/{scanId}/view")
    public ResponseEntity<PresignedUrlResponse> viewScan(
            @PathVariable Long scanId,
            @RequestHeader("Authorization") String authHeader) {
        return ResponseEntity.ok(diagnosticService.getPresignedUrl(scanId, authHeader));
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "Diagnostic service is running"));
    }
}
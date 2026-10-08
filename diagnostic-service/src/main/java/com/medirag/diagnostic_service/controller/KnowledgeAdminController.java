package com.medirag.diagnostic_service.controller;

import com.medirag.diagnostic_service.dto.KnowledgeChunkAdminRequest;
import com.medirag.diagnostic_service.service.KnowledgeIngestionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Admin-only endpoints for managing the RAG knowledge base.
 *
 * These endpoints are intentionally not secured with JWT in this
 * implementation — they are internal-only (not exposed through the Gateway
 * to patients) and should be protected at the network level in production.
 * Add role-based access (ROLE_ADMIN) when you implement admin users.
 */
@RestController
@RequestMapping("/api/diagnostics/admin/knowledge")
@RequiredArgsConstructor
@Slf4j
public class KnowledgeAdminController {

    private final KnowledgeIngestionService ingestionService;

    /**
     * Ingest a medical reference document into the knowledge base.
     *
     * Example request body:
     * {
     *   "sourceTitle": "Radiology Reference — Chest Pathology",
     *   "sourceType": "RADIOLOGY_FINDING",
     *   "conditionTag": "cardiomegaly",
     *   "content": "Cardiomegaly is defined as enlargement of the cardiac
     *               silhouette with a cardiothoracic ratio greater than 0.5
     *               on a PA chest X-ray. It is associated with heart failure,
     *               cardiomyopathy, and pericardial effusion..."
     * }
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> ingest(
            @Valid @RequestBody KnowledgeChunkAdminRequest request) {
        log.info("Knowledge ingestion requested: title='{}', type={}",
                request.getSourceTitle(), request.getSourceType());
        try {
            int chunksIngested = ingestionService.ingest(request);
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                    "status", "SUCCESS",
                    "chunksIngested", chunksIngested,
                    "sourceTitle", request.getSourceTitle(),
                    "timestamp", LocalDateTime.now().toString()
            ));
        } catch (Exception e) {
            log.error("Ingestion failed: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "status", "FAILED",
                    "error", e.getMessage(),
                    "timestamp", LocalDateTime.now().toString()
            ));
        }
    }

    /**
     * Returns current knowledge base statistics.
     * Use this to confirm ingestion worked.
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> stats() {
        KnowledgeIngestionService.KnowledgeBaseStats s = ingestionService.getStats();
        return ResponseEntity.ok(Map.of(
                "totalChunks", s.totalChunks(),
                "status", s.totalChunks() > 0 ? "POPULATED" : "EMPTY",
                "timestamp", LocalDateTime.now().toString()
        ));
    }
}
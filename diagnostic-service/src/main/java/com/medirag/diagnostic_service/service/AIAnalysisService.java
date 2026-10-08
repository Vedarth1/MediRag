package com.medirag.diagnostic_service.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medirag.diagnostic_service.entity.DiagnosticReport;
import com.medirag.diagnostic_service.entity.Finding;
import com.medirag.diagnostic_service.entity.ScanUpload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class AIAnalysisService {

    @Value("${openai.api-key}")
    private String apiKey;

    @Value("${openai.api-url}")
    private String apiUrl;

    @Value("${openai.model}")
    private String model;

    @Value("${rag.preliminary-vision-max-tokens:150}")
    private int stage1MaxTokens;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final MinioService minioService;
    private final RetrievalService retrievalService;
    private final PromptBuilderService promptBuilder;

    // ══ IMAGE PIPELINE ════════════════════════════════════════════════════

    /**
     * Two-stage RAG pipeline for X-ray and medical images.
     *
     * Stage 1: Lightweight Vision call → extract radiological terms as text
     * Stage 2: Full Vision call with image + retrieved medical context
     */
    public DiagnosticReport analyseXray(ScanUpload scan) {
        try {
            String base64Image = minioService.getBase64Encoded(scan.getFileUrl());
            String mimeType    = scan.getContentType() != null
                    ? scan.getContentType() : "image/jpeg";
            String imageDataUrl = "data:" + mimeType + ";base64," + base64Image;

            // ── Stage 1 — Extract radiological terms ────────────────────
            log.info("Stage 1: extracting radiological terms from scan {}", scan.getId());
            String radiologicalTerms = extractRadiologicalTerms(imageDataUrl);
            log.info("Stage 1 terms: '{}'", radiologicalTerms);

            // ── Retrieval — Find relevant knowledge chunks ───────────────
            String retrievedContext = retrievalService.retrieveForImage(radiologicalTerms);
            log.info("Retrieved context length: {} chars for scan {}",
                    retrievedContext.length(), scan.getId());

            // ── Stage 2 — Full analysis with retrieved context ───────────
            log.info("Stage 2: full analysis for scan {}", scan.getId());
            String analysisJson = performImageAnalysis(imageDataUrl, retrievedContext);
            return parseAnalysisResponse(analysisJson, scan);

        } catch (Exception e) {
            log.error("Image analysis failed for scan {}: {}", scan.getId(), e.getMessage(), e);
            return buildFallbackReport(scan);
        }
    }

    /**
     * Stage 1 — minimal Vision call to extract radiological terms.
     * Uses low max_tokens to keep this call fast and cheap.
     */
    private String extractRadiologicalTerms(String imageDataUrl) {
        try {
            Map<String, Object> requestBody = Map.of(
                "model", model,
                "messages", List.of(
                    Map.of("role", "system", "content",
                        promptBuilder.buildStage1SystemPrompt()),
                    Map.of("role", "user", "content", List.of(
                        Map.of("type", "text",
                               "text", promptBuilder.buildStage1UserPrompt()),
                        Map.of("type", "image_url",
                               "image_url", Map.of("url", imageDataUrl, "detail", "low"))
                    ))
                ),
                "max_tokens", stage1MaxTokens
            );

            ResponseEntity<Map> response = restTemplate.postForEntity(
                    apiUrl, new HttpEntity<>(requestBody, buildHeaders()), Map.class);

            String terms = extractContent(response);
            return terms != null ? terms.trim() : "";

        } catch (Exception e) {
            // Stage 1 failing is not fatal — retrieval will return empty context
            // and Stage 2 will still produce a report from model knowledge alone
            log.warn("Stage 1 term extraction failed: {} — proceeding without retrieval",
                    e.getMessage());
            return "";
        }
    }

    /**
     * Stage 2 — full structured analysis with retrieved context injected.
     */
    private String performImageAnalysis(String imageDataUrl, String retrievedContext) {
        Map<String, Object> requestBody = Map.of(
            "model", model,
            "messages", List.of(
                Map.of("role", "system", "content",
                    promptBuilder.buildStage2SystemPrompt(retrievedContext)),
                Map.of("role", "user", "content", List.of(
                    Map.of("type", "text",
                           "text", promptBuilder.buildStage2UserPrompt()),
                    Map.of("type", "image_url",
                           "image_url", Map.of("url", imageDataUrl, "detail", "high"))
                ))
            ),
            "max_tokens", 1500
        );

        ResponseEntity<Map> response = restTemplate.postForEntity(
                apiUrl, new HttpEntity<>(requestBody, buildHeaders()), Map.class);

        return extractContent(response);
    }

    // ══ REPORT PIPELINE ═══════════════════════════════════════════════════

    /**
     * Single-stage RAG pipeline for text-based medical reports (PDF/DOCX/TXT).
     *
     * reportChunks  — text chunks from ReportTextExtractionService
     * fullReportText — the complete extracted text (for the prompt context)
     */
    public DiagnosticReport analyseReport(ScanUpload scan,
                                           List<String> reportChunks,
                                           String fullReportText) {
        try {
            log.info("Report pipeline: retrieving context for {} chunks, scan {}",
                    reportChunks.size(), scan.getId());

            // ── Retrieval — embed report chunks, find relevant knowledge ─
            String retrievedContext = retrievalService.retrieveForReport(reportChunks);
            log.info("Retrieved context length: {} chars for report scan {}",
                    retrievedContext.length(), scan.getId());

            // ── Single LLM call — report text + retrieved context ────────
            log.info("Report analysis: calling LLM for scan {}", scan.getId());
            String analysisJson = performReportAnalysis(fullReportText, retrievedContext);

            DiagnosticReport report = parseAnalysisResponse(analysisJson, scan);

            // Store extracted text on the report for audit / display
            report.setReportText(fullReportText);
            return report;

        } catch (Exception e) {
            log.error("Report analysis failed for scan {}: {}", scan.getId(), e.getMessage(), e);
            return buildFallbackReport(scan);
        }
    }

    /**
     * LLM call for the report pipeline — text only, no image.
     * Uses the same model endpoint as image pipeline (Groq/OpenAI
     * both support text-only requests with the same API shape).
     */
    private String performReportAnalysis(String fullReportText, String retrievedContext) {
        Map<String, Object> requestBody = Map.of(
            "model", model,
            "messages", List.of(
                Map.of("role", "system", "content",
                    promptBuilder.buildReportSystemPrompt(fullReportText, retrievedContext)),
                Map.of("role", "user", "content",
                    promptBuilder.buildReportUserPrompt())
            ),
            "max_tokens", 1500
        );

        ResponseEntity<Map> response = restTemplate.postForEntity(
                apiUrl, new HttpEntity<>(requestBody, buildHeaders()), Map.class);

        return extractContent(response);
    }

    // ══ SHARED ════════════════════════════════════════════════════════════

    private HttpHeaders buildHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);
        return headers;
    }

    private String extractContent(ResponseEntity<Map> response) {
        List<Map<String, Object>> choices =
            (List<Map<String, Object>>) response.getBody().get("choices");
        Map<String, Object> message =
            (Map<String, Object>) choices.get(0).get("message");
        return (String) message.get("content");
    }

    private DiagnosticReport parseAnalysisResponse(String rawContent, ScanUpload scan) {
        try {
            String clean = rawContent
                    .replaceAll("(?s)```json\\s*", "")
                    .replaceAll("(?s)```\\s*", "")
                    .trim();

            Map<String, Object> parsed = objectMapper.readValue(clean, Map.class);

            DiagnosticReport report = DiagnosticReport.builder()
                    .scan(scan)
                    .summary((String) parsed.get("summary"))
                    .overallConfidence(toDouble(parsed.get("overallConfidence")))
                    .findings(new ArrayList<>())
                    .build();

            List<Map<String, Object>> findingsData =
                (List<Map<String, Object>>) parsed.get("findings");

            if (findingsData != null) {
                for (Map<String, Object> f : findingsData) {
                    Finding finding = Finding.builder()
                            .report(report)
                            .condition((String) f.get("condition"))
                            .confidence(toDouble(f.get("confidence")))
                            .severity(parseSeverity((String) f.get("severity")))
                            .location((String) f.get("location"))
                            .boundingBox(objectMapper.writeValueAsString(
                                    f.get("boundingBox")))
                            .build();
                    report.getFindings().add(finding);
                }
            }

            return report;

        } catch (Exception e) {
            log.error("Failed to parse AI response for scan {}: {}",
                    scan.getId(), e.getMessage());
            return buildFallbackReport(scan);
        }
    }

    private DiagnosticReport buildFallbackReport(ScanUpload scan) {
        DiagnosticReport report = DiagnosticReport.builder()
                .scan(scan)
                .summary("Automated analysis could not be completed. " +
                         "Please consult a radiologist.")
                .overallConfidence(0.0)
                .findings(new ArrayList<>())
                .build();

        report.getFindings().add(Finding.builder()
                .report(report)
                .condition("Manual review required")
                .confidence(0.0)
                .severity(Finding.Severity.NORMAL)
                .location("N/A")
                .build());
        return report;
    }

    private Finding.Severity parseSeverity(String s) {
        try   { return Finding.Severity.valueOf(s.toUpperCase()); }
        catch (Exception e) { return Finding.Severity.NORMAL; }
    }

    private Double toDouble(Object val) {
        if (val instanceof Double) return (Double) val;
        if (val instanceof Number) return ((Number) val).doubleValue();
        return null;
    }
}
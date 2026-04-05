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

    @Value("${minio.endpoint}")
    private String minioEndpoint;

    @Value("${minio.scan-bucket}")
    private String scanBucket;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper;
    private final MinioService minioService;

    public DiagnosticReport analyseXray(ScanUpload scan) {
        try {
            // Generate a short-lived presigned URL for OpenAI to access the image
            String imageUrl = minioService.generatePresignedUrl(scan.getFileUrl());

            // Build the vision API request
            Map<String, Object> requestBody = Map.of(
                "model", model,
                "messages", List.of(
                    Map.of("role", "system", "content",
                        "You are a radiologist AI assistant. Analyse the provided medical image and " +
                        "respond ONLY with a JSON object. No markdown, no explanation."),
                    Map.of("role", "user", "content", List.of(
                        // Text part of the message
                        Map.of("type", "text", "text", buildAnalysisPrompt()),
                        // Image part — OpenAI fetches it via the URL
                        Map.of("type", "image_url",
                               "image_url", Map.of("url", imageUrl, "detail", "high"))
                    ))
                ),
                "max_tokens", 1500
            );

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey);

            ResponseEntity<Map> response = restTemplate.postForEntity(
                apiUrl, new HttpEntity<>(requestBody, headers), Map.class
            );

            String content = extractContent(response);
            return parseAnalysisResponse(content, scan);

        } catch (Exception e) {
            log.error("AI analysis failed for scan {}: {}", scan.getId(), e.getMessage());
            return buildFallbackReport(scan);
        }
    }

    private String buildAnalysisPrompt() {
        return """
            Analyse this medical scan and respond with ONLY this JSON structure:
            {
              "summary": "Overall assessment in 2-3 sentences",
              "overallConfidence": 0.85,
              "findings": [
                {
                  "condition": "Condition name",
                  "confidence": 0.90,
                  "severity": "NORMAL|MILD|MODERATE|SEVERE|CRITICAL",
                  "location": "Anatomical location",
                  "boundingBox": {"x": 0, "y": 0, "width": 100, "height": 100}
                }
              ]
            }
            If the image appears normal, include one finding with condition "No significant abnormality" and severity "NORMAL".
            """;
    }

    private String extractContent(ResponseEntity<Map> response) {
        List<Map<String, Object>> choices =
            (List<Map<String, Object>>) response.getBody().get("choices");
        Map<String, Object> message =
            (Map<String, Object>) choices.get(0).get("message");
        return (String) message.get("content");
    }

    private DiagnosticReport parseAnalysisResponse(String jsonContent, ScanUpload scan) {
        try {
            // Strip any markdown fences just in case
            String clean = jsonContent
                .replaceAll("```json", "")
                .replaceAll("```", "")
                .trim();

            Map<String, Object> parsed = objectMapper.readValue(clean, Map.class);

            DiagnosticReport report = DiagnosticReport.builder()
                    .scan(scan)
                    .summary((String) parsed.get("summary"))
                    .overallConfidence(toDouble(parsed.get("overallConfidence")))
                    .findings(new ArrayList<>())
                    .build();

            // Parse each finding
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
                            .boundingBox(objectMapper.writeValueAsString(f.get("boundingBox")))
                            .build();
                    report.getFindings().add(finding);
                }
            }

            return report;

        } catch (Exception e) {
            log.error("Failed to parse AI response: {}", e.getMessage());
            return buildFallbackReport(scan);
        }
    }

    private DiagnosticReport buildFallbackReport(ScanUpload scan) {
        DiagnosticReport report = DiagnosticReport.builder()
                .scan(scan)
                .summary("Automated analysis could not be completed. Please consult a radiologist for manual review.")
                .overallConfidence(0.0)
                .findings(new ArrayList<>())
                .build();

        Finding fallback = Finding.builder()
                .report(report)
                .condition("Manual review required")
                .confidence(0.0)
                .severity(Finding.Severity.NORMAL)
                .location("N/A")
                .build();

        report.getFindings().add(fallback);
        return report;
    }

    private Finding.Severity parseSeverity(String severity) {
        try {
            return Finding.Severity.valueOf(severity.toUpperCase());
        } catch (Exception e) {
            return Finding.Severity.NORMAL;
        }
    }

    private Double toDouble(Object val) {
        if (val instanceof Double) return (Double) val;
        if (val instanceof Number) return ((Number) val).doubleValue();
        return null;
    }
}
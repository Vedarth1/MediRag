package com.medirag.diagnostic_service.dto;

import lombok.*;
import java.time.LocalDateTime;
import java.util.List;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class DiagnosticReportResponse {
    private Long id;
    private Long scanId;
    private String fileName;
    private String summary;
    private Double overallConfidence;
    private List<FindingResponse> findings;
    private String reportPdfUrl;
    private LocalDateTime generatedAt;
}
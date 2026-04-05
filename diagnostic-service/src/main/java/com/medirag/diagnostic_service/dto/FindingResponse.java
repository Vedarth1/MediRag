package com.medirag.diagnostic_service.dto;

import lombok.*;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class FindingResponse {
    private Long id;
    private String condition;
    private Double confidence;
    private String severity;
    private String location;
    private String boundingBox;
}
package com.medirag.diagnostic_service.dto;

import lombok.*;

/**
 * Internal DTO carrying a retrieved chunk and its similarity score.
 * Used only within the diagnostic-service — never serialized to HTTP response.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RetrievedChunkDto {
    private Long   chunkId;
    private String sourceTitle;
    private String sourceType;
    private String content;
    private double similarityScore;
}
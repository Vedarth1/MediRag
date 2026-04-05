package com.medirag.diagnostic_service.dto;

import lombok.*;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class PresignedUrlResponse {
    private String url;
    private int expiryMinutes;
    private String fileName;
}
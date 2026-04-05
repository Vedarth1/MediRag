package com.medirag.diagnostic_service.dto;

import lombok.*;
import java.time.LocalDateTime;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ScanUploadResponse {
    private Long id;
    private String fileName;
    private String scanType;
    private String status;
    private LocalDateTime uploadedAt;
    private boolean reportReady;
}
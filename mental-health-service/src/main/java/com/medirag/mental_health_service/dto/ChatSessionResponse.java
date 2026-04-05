package com.medirag.mental_health_service.dto;

import java.time.LocalDateTime;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatSessionResponse {
    private Long id;
    private String title;
    private Boolean isActive;
    private LocalDateTime createdAt;
    private LocalDateTime lastMessageAt;
    private int messageCount;
}
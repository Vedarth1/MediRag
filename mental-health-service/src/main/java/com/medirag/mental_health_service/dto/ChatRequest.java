package com.medirag.mental_health_service.dto;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ChatRequest {
    @NotBlank(message = "Message cannot be empty")
    private String message;
}

package com.medirag.mental_health_service.dto;

import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResourceResponse {
    private Long id;
    private String title;
    private String type;
    private String content;
    private String category;
    private Integer durationMinutes;
}
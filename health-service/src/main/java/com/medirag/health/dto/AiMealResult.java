package com.medirag.health.dto;

import lombok.*;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AiMealResult {
    private String mealsJson;
    private boolean aiGenerated;
}
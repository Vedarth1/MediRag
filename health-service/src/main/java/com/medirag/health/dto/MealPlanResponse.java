package com.medirag.health.dto;

import lombok.*;
import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MealPlanResponse {
    private Long id;
    private LocalDate date;
    private Integer totalCalories;
    private Double proteinG;
    private Double carbsG;
    private Double fatG;
    private String mealsJson;
    private Boolean aiGenerated;
}
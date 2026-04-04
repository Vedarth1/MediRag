package com.medirag.health.dto;

import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HealthProfileResponse {
    private Long id;
    private Long userId;
    private Double heightCm;
    private Double weightKg;
    private String bloodType;
    private String allergies;
    private String conditions;
    private Double bmi;
    private String bmiCategory;   // Underweight / Normal / Overweight / Obese
}
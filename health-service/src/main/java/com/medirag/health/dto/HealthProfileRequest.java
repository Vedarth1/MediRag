package com.medirag.health.dto;

import lombok.Data;

@Data
public class HealthProfileRequest {
    private Double heightCm;
    private Double weightKg;
    private String bloodType;
    private String allergies;
    private String conditions;
}
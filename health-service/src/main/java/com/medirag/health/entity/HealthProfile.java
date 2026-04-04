package com.medirag.health.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "health_profiles", schema = "health")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HealthProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // userId from JWT — no FK to auth schema
    @Column(nullable = false, unique = true)
    private Long userId;

    private Double heightCm;
    private Double weightKg;

    private String bloodType;    // A+, B-, O+, AB+ etc.

    // Store as comma-separated strings — simple for this scale
    private String allergies;    // "peanuts,dairy,gluten"
    private String conditions;   // "diabetes,hypertension"

    // Computed — stored for quick access
    private Double bmi;

    @PrePersist
    @PreUpdate
    protected void computeBmi() {
        if (heightCm != null && weightKg != null && heightCm > 0) {
            double heightM = heightCm / 100.0;
            this.bmi = Math.round((weightKg / (heightM * heightM)) * 10.0) / 10.0;
        }
    }
}
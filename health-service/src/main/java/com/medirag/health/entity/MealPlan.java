package com.medirag.health.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "meal_plans", schema = "health")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MealPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private LocalDate date;

    private Integer totalCalories;
    private Double proteinG;
    private Double carbsG;
    private Double fatG;

    // Full AI-generated plan stored as JSON string
    @Column(columnDefinition = "TEXT")
    private String mealsJson;

    // Whether this was AI-generated or manually entered
    @Column(nullable = false)
    @Builder.Default
    private Boolean aiGenerated = false;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
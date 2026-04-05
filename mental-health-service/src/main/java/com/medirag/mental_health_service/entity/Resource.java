package com.medirag.mental_health_service.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "resources", schema = "mental_health")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Resource {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ResourceType type;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;          // instructions or description

    @Column(nullable = false)
    private String category;         // "anxiety", "stress", "sleep", "mood"

    private Integer durationMinutes; // how long the exercise takes

    public enum ResourceType {
        BREATHING_EXERCISE,
        MEDITATION,
        JOURNALING_PROMPT,
        WELLNESS_TIP,
        GROUNDING_TECHNIQUE
    }
}
package com.medirag.diagnostic_service.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "findings", schema = "diagnostic")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Finding {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "report_id", nullable = false)
    private DiagnosticReport report;

    // e.g. "Pleural Effusion", "Cardiomegaly", "Pneumonia"
    @Column(nullable = false)
    private String condition;

    // 0.0 to 1.0 — AI's confidence this finding is present
    private Double confidence;

    @Enumerated(EnumType.STRING)
    private Severity severity;

    // Anatomical location — e.g. "Left lower lobe", "Right hilum"
    private String location;

    // Bounding box coordinates from YOLO — stored as JSON string
    // e.g. {"x": 120, "y": 80, "width": 200, "height": 150}
    @Column(columnDefinition = "TEXT")
    private String boundingBox;

    public enum Severity {
        NORMAL, MILD, MODERATE, SEVERE, CRITICAL
    }
}
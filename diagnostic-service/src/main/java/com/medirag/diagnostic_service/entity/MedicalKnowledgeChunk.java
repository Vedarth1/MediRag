package com.medirag.diagnostic_service.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "knowledge_chunks", schema = "diagnostic")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MedicalKnowledgeChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "source_title", nullable = false)
    private String sourceTitle;        // e.g. "Radiology Reference Guide - Chest Pathology"

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 50)
    private SourceType sourceType;

    @Column(name = "condition_tag", length = 100)
    private String conditionTag;       // e.g. "cardiomegaly" — nullable, used for optional filtering

    @Column(name = "chunk_index", nullable = false)
    private Integer chunkIndex;        // position within the source document

    // Mapped as a plain float[] — the actual `vector` SQL type conversion
    // happens via the custom Hibernate type registered in VectorType.java
    @Column(nullable = false, columnDefinition = "vector(384)")
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.OTHER)
    private float[] embedding;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    public enum SourceType {
        DISEASE,
        SYMPTOM,
        RADIOLOGY_FINDING,
        DIFFERENTIAL_DIAGNOSIS,
        TREATMENT_GUIDELINE,
        REFERENCE_DOCUMENT
    }
}
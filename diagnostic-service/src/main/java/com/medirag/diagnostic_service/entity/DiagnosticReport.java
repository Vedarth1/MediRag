package com.medirag.diagnostic_service.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "diagnostic_reports", schema = "diagnostic")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DiagnosticReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // One-to-one with ScanUpload
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "scan_id", nullable = false, unique = true)
    private ScanUpload scan;

    // AI-generated summary paragraph
    @Column(columnDefinition = "TEXT")
    private String summary;

    // Overall confidence score 0.0 to 1.0
    private Double overallConfidence;

    // Detailed findings as a list of Finding entities
    @OneToMany(mappedBy = "report", cascade = CascadeType.ALL,
               fetch = FetchType.LAZY, orphanRemoval = true)
    @Builder.Default
    private List<Finding> findings = new ArrayList<>();

    // URL to the generated PDF report in MinIO (future feature)
    private String reportPdfUrl;

    @Column(nullable = false, updatable = false)
    private LocalDateTime generatedAt;

    @PrePersist
    protected void onCreate() {
        generatedAt = LocalDateTime.now();
    }
}
package com.medirag.diagnostic_service.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "scan_uploads", schema = "diagnostic")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScanUpload {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long patientId;

    @Column(nullable = false)
    private String patientEmail;

    @Column(nullable = false)
    private String fileName;

    // Full MinIO object path — e.g. xrays/42/uuid-chest.jpg
    @Column(nullable = false)
    private String fileUrl;

    @Column(nullable = false)
    private String contentType;   // image/jpeg, image/png, application/pdf

    private Long fileSizeBytes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private ScanType scanType = ScanType.XRAY;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private ScanStatus status = ScanStatus.UPLOADED;

    // One scan has one report (generated after analysis)
    @OneToOne(mappedBy = "scan", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private DiagnosticReport report;

    @Column(nullable = false, updatable = false)
    private LocalDateTime uploadedAt;

    @PrePersist
    protected void onCreate() {
        uploadedAt = LocalDateTime.now();
    }

    public enum ScanType {
        XRAY, MRI, CT_SCAN, ULTRASOUND
    }

    public enum ScanStatus {
        UPLOADED,    // file stored in MinIO
        ANALYSING,   // AI is processing
        COMPLETED,   // report generated
        FAILED       // analysis failed
    }
}
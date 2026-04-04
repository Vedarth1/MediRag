package com.medirag.health.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "sleep_logs", schema = "health")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SleepLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private LocalDate date;

    @Column(nullable = false)
    private LocalDateTime bedTime;

    @Column(nullable = false)
    private LocalDateTime wakeTime;

    // Computed from bedTime and wakeTime
    private Double durationHours;

    @Enumerated(EnumType.STRING)
    private Quality quality;

    @PrePersist
    @PreUpdate
    protected void computeDuration() {
        if (bedTime != null && wakeTime != null) {
            long minutes = java.time.Duration.between(bedTime, wakeTime).toMinutes();
            this.durationHours = Math.round((minutes / 60.0) * 10.0) / 10.0;
        }
    }

    public enum Quality {
        POOR, FAIR, GOOD, EXCELLENT
    }
}
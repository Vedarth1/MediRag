package com.medirag.health.dto;

import lombok.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SleepLogResponse {
    private Long id;
    private LocalDate date;
    private LocalDateTime bedTime;
    private LocalDateTime wakeTime;
    private Double durationHours;
    private String quality;
}
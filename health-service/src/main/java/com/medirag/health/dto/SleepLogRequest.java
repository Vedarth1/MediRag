package com.medirag.health.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
public class SleepLogRequest {

    @NotNull
    private LocalDate date;

    @NotNull
    private LocalDateTime bedTime;

    @NotNull
    private LocalDateTime wakeTime;

    private String quality;   // POOR / FAIR / GOOD / EXCELLENT
}
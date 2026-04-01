package com.medirag.appointment.dto;

import lombok.*;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TimeSlotResponse {
    private Long id;
    private Long doctorId;
    private String doctorName;
    private LocalDateTime slotTime;
    private Boolean isBooked;
}
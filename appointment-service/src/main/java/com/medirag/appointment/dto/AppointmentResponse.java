package com.medirag.appointment.dto;

import lombok.*;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AppointmentResponse {
    private Long id;
    private Long patientId;
    private String patientEmail;
    private String doctorName;
    private String specialization;
    private LocalDateTime slotTime;
    private String status;
    private LocalDateTime bookedAt;
}
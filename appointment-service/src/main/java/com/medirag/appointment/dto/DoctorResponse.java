package com.medirag.appointment.dto;

import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DoctorResponse {
    private Long id;
    private String name;
    private String specialization;
    private String email;
    private String phone;
    private Boolean available;
}
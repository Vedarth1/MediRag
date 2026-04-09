package com.medirag.appointment.dto;

import lombok.Data;

@Data
public class DoctorProfileRequest {
    private String name;
    private String specialization;
    private String phone;
}
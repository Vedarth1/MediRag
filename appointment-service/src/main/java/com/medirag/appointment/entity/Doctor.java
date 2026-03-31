package com.medirag.appointment.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "doctors", schema = "appointment")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Doctor {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String specialization;

    @Column(nullable = false, unique = true)
    private String email;

    private String phone;

    @Column(nullable = false)
    @Builder.Default
    private Boolean available = true;
}
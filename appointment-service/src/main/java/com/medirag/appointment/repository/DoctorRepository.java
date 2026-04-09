package com.medirag.appointment.repository;

import com.medirag.appointment.entity.Doctor;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface DoctorRepository extends JpaRepository<Doctor, Long> {
    List<Doctor> findBySpecializationIgnoreCase(String specialization);
    List<Doctor> findByAvailableTrue();
    boolean existsByEmail(String email);
}
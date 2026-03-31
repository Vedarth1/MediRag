package com.medirag.appointment.repository;

import com.medirag.appointment.entity.TimeSlot;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface TimeSlotRepository extends JpaRepository<TimeSlot, Long> {
    List<TimeSlot> findByDoctorIdAndIsBookedFalse(Long doctorId);
}
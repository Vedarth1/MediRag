package com.medirag.appointment.repository;

import com.medirag.appointment.entity.TimeSlot;

import io.lettuce.core.dynamic.annotation.Param;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface TimeSlotRepository extends JpaRepository<TimeSlot, Long> {
    List<TimeSlot> findByDoctorIdAndIsBookedFalse(Long doctorId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)              //Optimistic Locking vs Pessimistic locking
    @Query("""
        SELECT t
        FROM TimeSlot t
        WHERE t.id = :slotId
    """)
    Optional<TimeSlot> findByIdForUpdate(@Param("slotId") Long slotId);
}
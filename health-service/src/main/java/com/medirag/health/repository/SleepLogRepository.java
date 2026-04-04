package com.medirag.health.repository;

import com.medirag.health.entity.SleepLog;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDate;
import java.util.List;

public interface SleepLogRepository extends JpaRepository<SleepLog, Long> {
    List<SleepLog> findByUserIdOrderByDateDesc(Long userId);

    // Date range query — Spring Data parses this method name automatically
    List<SleepLog> findByUserIdAndDateBetweenOrderByDateAsc(
            Long userId, LocalDate from, LocalDate to);
}
package com.medirag.health.repository;

import com.medirag.health.entity.MealPlan;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDate;
import java.util.List;

public interface MealPlanRepository extends JpaRepository<MealPlan, Long> {
    List<MealPlan> findByUserIdOrderByDateDesc(Long userId);
    List<MealPlan> findByUserIdAndDateBetween(Long userId, LocalDate from, LocalDate to);
}
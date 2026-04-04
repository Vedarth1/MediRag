package com.medirag.health.repository;

import com.medirag.health.entity.HealthProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface HealthProfileRepository extends JpaRepository<HealthProfile, Long> {
    Optional<HealthProfile> findByUserId(Long userId);
    boolean existsByUserId(Long userId);
}
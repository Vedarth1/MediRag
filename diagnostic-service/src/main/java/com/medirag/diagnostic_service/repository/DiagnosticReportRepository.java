package com.medirag.diagnostic_service.repository;

import com.medirag.diagnostic_service.entity.DiagnosticReport;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface DiagnosticReportRepository extends JpaRepository<DiagnosticReport, Long> {
    Optional<DiagnosticReport> findByScanId(Long scanId);
}
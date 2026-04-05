package com.medirag.diagnostic_service.repository;

import com.medirag.diagnostic_service.entity.ScanUpload;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface ScanUploadRepository extends JpaRepository<ScanUpload, Long> {
    List<ScanUpload> findByPatientIdOrderByUploadedAtDesc(Long patientId);
    Optional<ScanUpload> findByIdAndPatientId(Long id, Long patientId);
}
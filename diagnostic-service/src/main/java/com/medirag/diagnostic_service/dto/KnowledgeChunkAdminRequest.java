package com.medirag.diagnostic_service.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class KnowledgeChunkAdminRequest {

    @NotBlank(message = "sourceTitle is required")
    private String sourceTitle;       // e.g. "Radiology Reference Guide - Chest"

    @NotBlank(message = "sourceType is required — one of: DISEASE, SYMPTOM, " +
                        "RADIOLOGY_FINDING, DIFFERENTIAL_DIAGNOSIS, " +
                        "TREATMENT_GUIDELINE, REFERENCE_DOCUMENT")
    private String sourceType;

    private String conditionTag;      // optional — e.g. "cardiomegaly"

    @NotBlank(message = "content is required")
    private String content;           // full raw text of the reference document
}
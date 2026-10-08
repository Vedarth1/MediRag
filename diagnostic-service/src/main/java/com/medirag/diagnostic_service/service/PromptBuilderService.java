package com.medirag.diagnostic_service.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Constructs final prompts for both RAG pipelines.
 *
 * IMAGE PIPELINE:
 *   Stage 1 prompt — extract radiological terms (cheap, minimal tokens)
 *   Stage 2 prompt — full structured analysis with retrieved context injected
 *
 * REPORT PIPELINE:
 *   Single prompt — report text + retrieved context + structured analysis template
 *
 * All methods are pure functions — no dependencies, no state, no DB access.
 * Easy to unit test and easy to tune prompt wording without touching
 * any other class.
 */
@Service
@Slf4j
public class PromptBuilderService {

    // ── IMAGE PIPELINE ────────────────────────────────────────────────────

    /**
     * Stage 1 system prompt.
     * Instructs the Vision model to extract radiological terms only —
     * not to perform full analysis. Keeps Stage 1 fast and cheap.
     */
    public String buildStage1SystemPrompt() {
        return """
                You are a radiologist assistant.
                Your ONLY task is to list the key radiological findings
                visible in the provided medical image.
                Respond with ONLY a comma-separated list of medical terms.
                No sentences. No explanations. No punctuation except commas.
                Example: cardiomegaly, bilateral pleural effusion, consolidation
                """;
    }

    /**
     * Stage 1 user prompt — minimal, just asks for the term list.
     */
    public String buildStage1UserPrompt() {
        return "List the radiological findings visible in this image as " +
               "comma-separated medical terms only.";
    }

    /**
     * Stage 2 system prompt — full structured analysis.
     * Retrieved context is injected here so the model is explicitly
     * told to use it as reference material.
     *
     * @param retrievedContext  Formatted chunks from RetrievalService.
     *                          Empty string if knowledge base is empty or
     *                          retrieval failed — prompt degrades gracefully.
     */
    public String buildStage2SystemPrompt(String retrievedContext) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
                You are an expert radiologist AI assistant.
                Analyse the provided medical image and generate a structured
                diagnostic report.
                Respond ONLY with a valid JSON object.
                No markdown. No explanation. No code fences. Just raw JSON.
                """);

        if (retrievedContext != null && !retrievedContext.isBlank()) {
            sb.append("""

                Use the following verified medical reference knowledge to
                ground your analysis. Prioritise findings that align with
                this reference material:

                --- MEDICAL REFERENCE KNOWLEDGE ---
                """);
            sb.append(retrievedContext);
            sb.append("\n--- END OF REFERENCE KNOWLEDGE ---\n");
        } else {
            log.debug("No retrieved context available for Stage 2 — " +
                      "proceeding with model's pretrained knowledge only");
        }

        return sb.toString();
    }

    /**
     * Stage 2 user prompt — the structured JSON template the model must fill.
     * Unchanged from the original buildAnalysisPrompt() in AIAnalysisService,
     * but now lives here for centralisation.
     */
    public String buildStage2UserPrompt() {
        return """
                Analyse this medical scan and respond with ONLY this JSON:
                {
                  "summary": "2-3 sentence overall clinical assessment",
                  "overallConfidence": 0.85,
                  "findings": [
                    {
                      "condition": "Finding name",
                      "confidence": 0.90,
                      "severity": "NORMAL",
                      "location": "Anatomical location",
                      "boundingBox": {"x": 0, "y": 0, "width": 100, "height": 100}
                    }
                  ]
                }
                Severity must be one of: NORMAL, MILD, MODERATE, SEVERE, CRITICAL.
                If the image appears normal include one finding:
                condition "No significant abnormality", severity "NORMAL".
                """;
    }

    // ── REPORT PIPELINE ───────────────────────────────────────────────────

    /**
     * System prompt for the report pipeline.
     * The full extracted report text and retrieved context are both
     * injected so the model can cross-reference them.
     *
     * @param reportText       Full extracted text from the uploaded file
     * @param retrievedContext Retrieved knowledge chunks from pgvector
     */
    public String buildReportSystemPrompt(String reportText,
                                           String retrievedContext) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
                You are an expert medical AI assistant specialising in
                clinical report analysis.
                You will be given a medical report and reference knowledge.
                Generate a structured diagnostic summary.
                Respond ONLY with a valid JSON object.
                No markdown. No explanation. No code fences. Just raw JSON.
                """);

        if (retrievedContext != null && !retrievedContext.isBlank()) {
            sb.append("""

                Use the following verified medical reference knowledge to
                support your analysis:

                --- MEDICAL REFERENCE KNOWLEDGE ---
                """);
            sb.append(retrievedContext);
            sb.append("\n--- END OF REFERENCE KNOWLEDGE ---\n");
        }

        sb.append("""

                Analyse the following medical report:

                --- MEDICAL REPORT ---
                """);
        sb.append(reportText);
        sb.append("\n--- END OF REPORT ---\n");

        return sb.toString();
    }

    /**
     * User prompt for report pipeline — same JSON structure as image pipeline.
     * Using the same output schema means parseAnalysisResponse() in
     * AIAnalysisService works identically for both pipelines.
     */
    public String buildReportUserPrompt() {
        return """
                Analyse the medical report provided in the system context
                and respond with ONLY this JSON:
                {
                  "summary": "2-3 sentence overall clinical assessment",
                  "overallConfidence": 0.85,
                  "findings": [
                    {
                      "condition": "Condition or finding name",
                      "confidence": 0.90,
                      "severity": "NORMAL",
                      "location": "Body location or system affected",
                      "boundingBox": {"x": 0, "y": 0, "width": 0, "height": 0}
                    }
                  ]
                }
                Severity must be one of: NORMAL, MILD, MODERATE, SEVERE, CRITICAL.
                Set boundingBox to all zeros for report-based findings
                since there is no image to annotate.
                If the report indicates normal findings include one finding:
                condition "No significant abnormality", severity "NORMAL".
                """;
    }
}
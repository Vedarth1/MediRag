package com.medirag.diagnostic_service.service;

import com.medirag.diagnostic_service.dto.RetrievedChunkDto;
import com.medirag.diagnostic_service.entity.MedicalKnowledgeChunk;
import com.medirag.diagnostic_service.repository.KnowledgeChunkRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Retrieval layer for both RAG pipelines.
 *
 * IMAGE PIPELINE:
 *   Input  → radiological terms extracted by Stage 1 Vision call
 *            e.g. "cardiomegaly, pleural effusion, increased lung markings"
 *   Output → top-K relevant knowledge chunks from pgvector
 *
 * REPORT PIPELINE:
 *   Input  → list of text chunks extracted from the uploaded PDF/DOCX/TXT
 *   Output → aggregated top-K relevant knowledge chunks across all
 *            report chunks (deduplicated)
 *
 * Both outputs are available as:
 *   - formatted String (for prompt injection)
 *   - List<RetrievedChunkDto> (for debugging, admin stats, API responses)
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RetrievalService {

    private final EmbeddingClient embeddingClient;
    private final KnowledgeChunkRepository knowledgeChunkRepository;

    @Value("${rag.top-k:5}")
    private int topK;

    @Value("${rag.similarity-threshold:0.3}")
    private double similarityThreshold;

    // ── IMAGE PIPELINE ────────────────────────────────────────────────────

    /**
     * Called by AIAnalysisService after Stage 1 Vision call.
     *
     * @param radiologicalTerms  Comma-separated terms from Stage 1 Vision
     * @return                   Formatted context string ready for prompt injection.
     *                           Empty string if retrieval fails or KB is empty.
     */
    public String retrieveForImage(String radiologicalTerms) {
        List<RetrievedChunkDto> chunks = retrieveForImageStructured(radiologicalTerms);
        return formatChunksAsContext(chunks);
    }

    /**
     * Structured variant — returns RetrievedChunkDto list with similarity scores.
     * Use for debugging, admin endpoints, or when you need per-chunk metadata.
     */
    public List<RetrievedChunkDto> retrieveForImageStructured(String radiologicalTerms) {
        if (radiologicalTerms == null || radiologicalTerms.isBlank()) {
            log.warn("retrieveForImage called with empty terms — skipping retrieval");
            return List.of();
        }

        log.info("Retrieving knowledge for image terms: '{}'", radiologicalTerms);

        float[] queryEmbedding = embeddingClient.embed(radiologicalTerms);
        if (queryEmbedding == null) {
            log.warn("Embedding generation failed for image terms — proceeding without RAG context");
            return List.of();
        }

        List<MedicalKnowledgeChunk> rawChunks = searchSimilarChunks(queryEmbedding);
        List<RetrievedChunkDto> dtos = toDtos(rawChunks, queryEmbedding);

        log.info("Retrieved {} relevant chunks for image analysis", dtos.size());
        return dtos;
    }

    // ── REPORT PIPELINE ───────────────────────────────────────────────────

    /**
     * Called by AIAnalysisService for the report pipeline.
     *
     * @param reportChunks  Text chunks from DocumentChunkingService
     * @return              Aggregated, deduplicated context string
     */
    public String retrieveForReport(List<String> reportChunks) {
        List<RetrievedChunkDto> chunks = retrieveForReportStructured(reportChunks);
        return formatChunksAsContext(chunks);
    }

    /**
     * Structured variant — returns RetrievedChunkDto list with similarity scores.
     */
    public List<RetrievedChunkDto> retrieveForReportStructured(List<String> reportChunks) {
        if (reportChunks == null || reportChunks.isEmpty()) {
            log.warn("retrieveForReport called with empty chunks — skipping retrieval");
            return List.of();
        }

        log.info("Retrieving knowledge for {} report chunks", reportChunks.size());

        List<float[]> chunkEmbeddings = embeddingClient.embedBatch(reportChunks);
        if (chunkEmbeddings == null) {
            log.warn("Batch embedding failed for report chunks — proceeding without RAG context");
            return List.of();
        }

        Set<Long> seenIds = new LinkedHashSet<>();
        List<MedicalKnowledgeChunk> allRelevantChunks = new ArrayList<>();

        for (int i = 0; i < chunkEmbeddings.size(); i++) {
            float[] embedding = chunkEmbeddings.get(i);
            if (embedding == null) {
                log.warn("Null embedding at index {} — skipping this chunk", i);
                continue;
            }

            List<MedicalKnowledgeChunk> results = searchSimilarChunks(embedding);

            for (MedicalKnowledgeChunk chunk : results) {
                if (seenIds.add(chunk.getId())) {
                    allRelevantChunks.add(chunk);
                }
            }
        }

        // Cap total retrieved chunks at topK * 2
        int cap = topK * 2;
        List<MedicalKnowledgeChunk> capped = allRelevantChunks.size() > cap
                ? allRelevantChunks.subList(0, cap)
                : allRelevantChunks;

        // Compute similarity scores against the first embedding for scoring
        // (each chunk was retrieved by at least one embedding; we approximate
        // by computing similarity against the first query embedding for ranking)
        List<RetrievedChunkDto> dtos = capped.isEmpty() ? List.of() :
                toDtos(capped, chunkEmbeddings.get(0));

        log.info("Retrieved {} unique relevant chunks across {} report chunks",
                dtos.size(), reportChunks.size());
        return dtos;
    }

    // ── Shared internal methods ───────────────────────────────────────────

    private List<MedicalKnowledgeChunk> searchSimilarChunks(float[] queryEmbedding) {
        long totalChunks = knowledgeChunkRepository.count();
        if (totalChunks == 0) {
            log.warn("Knowledge base is empty — no chunks to retrieve. " +
                    "Ingest medical reference documents via POST /api/diagnostics/admin/knowledge");
            return List.of();
        }

        try {
            String vectorLiteral = embeddingClient.toVectorLiteral(queryEmbedding);
            List<MedicalKnowledgeChunk> results =
                    knowledgeChunkRepository.findSimilarChunks(vectorLiteral, topK);

            List<MedicalKnowledgeChunk> filtered = filterByThreshold(results, queryEmbedding);

            log.debug("pgvector returned {} chunks, {} passed threshold ({})",
                    results.size(), filtered.size(), similarityThreshold);

            return filtered;

        } catch (Exception e) {
            log.error("pgvector similarity search failed: {}", e.getMessage(), e);
            return List.of();
        }
    }

    private List<MedicalKnowledgeChunk> filterByThreshold(
            List<MedicalKnowledgeChunk> chunks,
            float[] queryEmbedding) {

        return chunks.stream()
                .filter(chunk -> {
                    if (chunk.getEmbedding() == null) return false;
                    double similarity = cosineSimilarity(queryEmbedding, chunk.getEmbedding());
                    boolean passes = similarity >= similarityThreshold;
                    if (!passes) {
                        log.debug("Chunk {} filtered out (similarity={:.3f} < threshold={})",
                                chunk.getId(), similarity, similarityThreshold);
                    }
                    return passes;
                })
                .collect(Collectors.toList());
    }

    private double cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length) {
            log.warn("Vector dimension mismatch: {} vs {}", a.length, b.length);
            return 0.0;
        }
        double dot = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
        }
        return dot;
    }

    /**
     * Converts MedicalKnowledgeChunk entities to RetrievedChunkDto,
     * computing similarity score against the query embedding.
     */
    private List<RetrievedChunkDto> toDtos(List<MedicalKnowledgeChunk> chunks,
                                            float[] queryEmbedding) {
        List<RetrievedChunkDto> dtos = new ArrayList<>(chunks.size());
        for (MedicalKnowledgeChunk chunk : chunks) {
            double score = chunk.getEmbedding() != null
                    ? cosineSimilarity(queryEmbedding, chunk.getEmbedding())
                    : 0.0;
            dtos.add(RetrievedChunkDto.builder()
                    .chunkId(chunk.getId())
                    .sourceTitle(chunk.getSourceTitle())
                    .sourceType(chunk.getSourceType().name())
                    .content(chunk.getContent())
                    .similarityScore(score)
                    .build());
        }
        return dtos;
    }

    /**
     * Formats RetrievedChunkDto list into a context string for prompt injection.
     */
    private String formatChunksAsContext(List<RetrievedChunkDto> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            RetrievedChunkDto chunk = chunks.get(i);
            sb.append("[").append(i + 1).append("] ")
              .append("SOURCE: ").append(chunk.getSourceTitle())
              .append(" | TYPE: ").append(chunk.getSourceType())
              .append(" | SIMILARITY: ").append(String.format("%.3f", chunk.getSimilarityScore()))
              .append("\n")
              .append(chunk.getContent())
              .append("\n\n");
        }
        return sb.toString().trim();
    }
}

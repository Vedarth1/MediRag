package com.medirag.diagnostic_service.service;

import com.medirag.diagnostic_service.dto.KnowledgeChunkAdminRequest;
import com.medirag.diagnostic_service.entity.MedicalKnowledgeChunk;
import com.medirag.diagnostic_service.repository.KnowledgeChunkRepository;
import com.pgvector.PGvector;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Admin-facing ingestion pipeline for the medical knowledge base.
 *
 * Flow:
 *   Raw text → DocumentChunkingService → List<IndexedChunk>
 *           → EmbeddingClient.embedBatch() → List<float[]>
 *           → KnowledgeChunk entities → KnowledgeChunkRepository.saveAll()
 *
 * This only runs when an admin explicitly POSTs to
 * /api/diagnostics/admin/knowledge — never during patient-facing flows.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class KnowledgeIngestionService {

    private final DocumentChunkingService chunkingService;
    private final EmbeddingClient embeddingClient;
    private final KnowledgeChunkRepository knowledgeChunkRepository;

    /**
     * Ingests a single knowledge document.
     * Chunks it, embeds all chunks in one batch call, saves to pgvector.
     *
     * @return number of chunks successfully ingested
     */
    @Transactional
    public int ingest(KnowledgeChunkAdminRequest request) {
        log.info("Starting ingestion: title='{}', type={}, contentLength={}chars",
                request.getSourceTitle(),
                request.getSourceType(),
                request.getContent().length());

        // Step 1 — chunk the raw content
        List<DocumentChunkingService.IndexedChunk> indexedChunks =
                chunkingService.chunkIndexed(request.getContent());

        if (indexedChunks.isEmpty()) {
            log.warn("Chunking produced 0 chunks for '{}' — skipping ingestion",
                    request.getSourceTitle());
            return 0;
        }

        log.info("Chunked '{}' into {} chunks", request.getSourceTitle(), indexedChunks.size());

        // Step 2 — extract plain text list for batch embedding
        List<String> texts = indexedChunks.stream()
                .map(DocumentChunkingService.IndexedChunk::text)
                .toList();

        // Step 3 — embed all chunks in one batch HTTP call to embedding-service
        List<float[]> embeddings = embeddingClient.embedBatch(texts);
        if (embeddings == null) {
            throw new RuntimeException(
                    "Embedding generation failed for '" + request.getSourceTitle() +
                    "'. embedding-service may be unavailable. Ingestion aborted.");
        }

        if (embeddings.size() != indexedChunks.size()) {
            throw new RuntimeException(
                    "Embedding count mismatch: expected " + indexedChunks.size() +
                    " but got " + embeddings.size() + ". Ingestion aborted.");
        }

        // Step 4 — build KnowledgeChunk entities and save in one batch
        List<MedicalKnowledgeChunk> chunks = new ArrayList<>();
        MedicalKnowledgeChunk.SourceType sourceType =
                MedicalKnowledgeChunk.SourceType.valueOf(request.getSourceType().toUpperCase());

        for (int i = 0; i < indexedChunks.size(); i++) {
            DocumentChunkingService.IndexedChunk indexed = indexedChunks.get(i);
            float[] embedding = embeddings.get(i);

            if (embedding == null) {
                log.warn("Null embedding at index {} for '{}' — skipping this chunk",
                        i, request.getSourceTitle());
                continue;
            }

            chunks.add(MedicalKnowledgeChunk.builder()
                    .content(indexed.text())
                    .sourceTitle(request.getSourceTitle())
                    .sourceType(sourceType)
                    .conditionTag(request.getConditionTag())
                    .chunkIndex(indexed.index())
                    .embedding(embedding)
                    .build());
        }

        knowledgeChunkRepository.saveAll(chunks);

        log.info("Ingested {} chunks from '{}' into knowledge base",
                chunks.size(), request.getSourceTitle());

        return chunks.size();
    }

    /**
     * Returns stats about the current knowledge base.
     * Used by KnowledgeAdminController's GET /stats endpoint.
     */
    public KnowledgeBaseStats getStats() {
        long totalChunks = knowledgeChunkRepository.count();
        return new KnowledgeBaseStats(totalChunks);
    }

    public record KnowledgeBaseStats(long totalChunks) {}
}
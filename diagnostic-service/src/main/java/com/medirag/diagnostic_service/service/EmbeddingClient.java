package com.medirag.diagnostic_service.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

/**
 * HTTP client for the embedding-service (Python/FastAPI/sentence-transformers).
 * This is the ONLY class in diagnostic-service that talks to embedding-service —
 * KnowledgeIngestionService and RetrievalService both go through this rather
 * than calling RestTemplate directly, so the integration details (URL,
 * timeouts, error handling, response shape) live in exactly one place.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EmbeddingClient {

    private final RestTemplate restTemplate;

    @Value("${embedding.service-url}")
    private String embeddingServiceUrl;

    private static final int EMBEDDING_DIMENSION = 384;

    /**
     * Embeds a single piece of text — used at query/retrieval time,
     * e.g. embedding a derived query string before similarity search.
     *
     * Returns null on failure rather than throwing, so callers
     * (RetrievalService) can fail open: skip retrieval and proceed
     * with the AI analysis ungrounded, matching the existing
     * buildFallbackReport() philosophy already used elsewhere in this service.
     */
    public float[] embed(String text) {
        if (text == null || text.isBlank()) {
            log.warn("embed() called with null/blank text — skipping");
            return null;
        }

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            Map<String, Object> requestBody = Map.of("text", text);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

            ResponseEntity<Map> response = restTemplate.postForEntity(
                    embeddingServiceUrl + "/embed", entity, Map.class
            );

            return extractSingleEmbedding(response);

        } catch (RestClientException e) {
            log.error("embedding-service call failed for /embed: {}", e.getMessage());
            return null;
        } catch (Exception e) {
            log.error("Unexpected error generating embedding: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * Embeds multiple texts in a single call — used during knowledge base
     * ingestion. Chunking a reference document can produce dozens of chunks;
     * batching avoids dozens of separate HTTP round-trips to embedding-service.
     *
     * Returns null on failure (not an empty list) so callers can clearly
     * distinguish "ingestion failed, retry" from "zero chunks to embed".
     */
    public List<float[]> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            log.warn("embedBatch() called with null/empty list — skipping");
            return null;
        }

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            Map<String, Object> requestBody = Map.of("texts", texts);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

            ResponseEntity<Map> response = restTemplate.postForEntity(
                    embeddingServiceUrl + "/embed/batch", entity, Map.class
            );

            return extractBatchEmbeddings(response);

        } catch (RestClientException e) {
            log.error("embedding-service call failed for /embed/batch ({} texts): {}",
                    texts.size(), e.getMessage());
            return null;
        } catch (Exception e) {
            log.error("Unexpected error generating batch embeddings: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * Converts a float[] embedding into pgvector's text input format:
     * '[0.123,-0.456,0.789,...]'
     *
     * This is the exact string format KnowledgeChunkRepository's native
     * @Query passes as :queryVector to be CAST(... AS vector) by Postgres.
     */
    public String toVectorLiteral(float[] embedding) {
        if (embedding == null) {
            throw new IllegalArgumentException("Cannot convert null embedding to vector literal");
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            sb.append(embedding[i]);
            if (i < embedding.length - 1) sb.append(",");
        }
        sb.append("]");
        return sb.toString();
    }

    // ── Response parsing helpers ─────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private float[] extractSingleEmbedding(ResponseEntity<Map> response) {
        Map<String, Object> body = response.getBody();
        if (body == null || !body.containsKey("embedding")) {
            log.error("embedding-service response missing 'embedding' field: {}", body);
            return null;
        }

        List<Double> raw = (List<Double>) body.get("embedding");
        return toFloatArray(raw);
    }

    @SuppressWarnings("unchecked")
    private List<float[]> extractBatchEmbeddings(ResponseEntity<Map> response) {
        Map<String, Object> body = response.getBody();
        if (body == null || !body.containsKey("embeddings")) {
            log.error("embedding-service response missing 'embeddings' field: {}", body);
            return null;
        }

        List<List<Double>> raw = (List<List<Double>>) body.get("embeddings");
        return raw.stream().map(this::toFloatArray).toList();
    }

    private float[] toFloatArray(List<Double> raw) {
        if (raw.size() != EMBEDDING_DIMENSION) {
            log.warn("Expected {}-dim embedding but got {} — model mismatch between " +
                    "embedding-service and KnowledgeChunk.embedding column?",
                    EMBEDDING_DIMENSION, raw.size());
        }
        float[] result = new float[raw.size()];
        for (int i = 0; i < raw.size(); i++) {
            result[i] = raw.get(i).floatValue();
        }
        return result;
    }
}
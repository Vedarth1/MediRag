package com.medirag.diagnostic_service.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Splits long text into overlapping chunks suitable for embedding.
 *
 * Used by two separate pipelines:
 * 1. KnowledgeIngestionService — chunks medical reference documents
 *    before embedding and storing in pgvector knowledge base
 * 2. ReportTextExtractionService — chunks extracted report text
 *    before embedding for per-chunk retrieval at analysis time
 *
 * Chunking strategy: sentence-aware splitting.
 * We do NOT cut blindly at character count — we find the nearest
 * sentence boundary so each chunk is a complete thought.
 * Overlap ensures context is not lost at chunk boundaries.
 */
@Service
@Slf4j
public class DocumentChunkingService {

    @Value("${rag.chunk-size:500}")
    private int chunkSize;

    @Value("${rag.chunk-overlap:50}")
    private int chunkOverlap;

    // Sentence boundary detection — splits on . ! ? followed by whitespace
    // We keep the delimiter attached to the preceding sentence (trailing split)
    private static final Pattern SENTENCE_BOUNDARY =
            Pattern.compile("(?<=[.!?])\\s+");

    /**
     * Splits text into overlapping chunks using sentence-aware boundaries.
     *
     * @param text  The full text to chunk (extracted from PDF/DOCX/TXT
     *              or raw medical reference content)
     * @return      Ordered list of text chunks ready to embed
     */
    public List<String> chunk(String text) {
        if (text == null || text.isBlank()) {
            log.warn("chunk() called with null/blank text — returning empty list");
            return List.of();
        }

        String normalised = normalise(text);

        // If text is short enough to be a single chunk, return it directly
        // — no splitting needed, avoids unnecessary fragmentation of
        // already-concise content like a lab result or short clinical note
        if (normalised.length() <= chunkSize) {
            log.debug("Text fits in single chunk ({} chars)", normalised.length());
            return List.of(normalised);
        }

        List<String> sentences = splitIntoSentences(normalised);
        List<String> chunks    = buildChunks(sentences);

        log.info("Chunked {} chars into {} chunks (chunkSize={}, overlap={})",
                normalised.length(), chunks.size(), chunkSize, chunkOverlap);

        return chunks;
    }

    /**
     * Chunks text and returns each chunk tagged with its index.
     * Used by KnowledgeIngestionService so it can set KnowledgeChunk.chunkIndex
     * for ordering/debugging — which chunk in the source document this came from.
     */
    public List<IndexedChunk> chunkIndexed(String text) {
        List<String> chunks = chunk(text);
        List<IndexedChunk> indexed = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            indexed.add(new IndexedChunk(i, chunks.get(i)));
        }
        return indexed;
    }

    // ── Internal splitting logic ─────────────────────────────────────────

    private String normalise(String text) {
        return text
                // Collapse multiple whitespace/newlines into single space
                .replaceAll("\\s+", " ")
                // Remove common PDF extraction artefacts
                .replaceAll("\\f", " ")        // form feed character
                .replaceAll("\\r", "")         // carriage return
                .trim();
    }

    private List<String> splitIntoSentences(String text) {
        String[] parts = SENTENCE_BOUNDARY.split(text);
        List<String> sentences = new ArrayList<>();
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                sentences.add(trimmed);
            }
        }
        return sentences;
    }

    private List<String> buildChunks(List<String> sentences) {
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        // Tracks sentences in the current chunk for overlap calculation
        List<String> currentSentences = new ArrayList<>();

        for (String sentence : sentences) {
            // If adding this sentence would exceed chunkSize,
            // save the current chunk and start a new one with overlap
            if (current.length() + sentence.length() + 1 > chunkSize
                    && current.length() > 0) {

                chunks.add(current.toString().trim());

                // Build overlap: take sentences from the end of the current
                // chunk that together are <= chunkOverlap characters
                current = new StringBuilder(buildOverlap(currentSentences));
                currentSentences.clear();

                // Add the overlap sentences back to sentence tracking
                // so the next chunk's overlap calculation is correct
                if (current.length() > 0) {
                    currentSentences.add(current.toString().trim());
                }
            }

            current.append(sentence).append(" ");
            currentSentences.add(sentence);
        }

        // Don't forget the last partial chunk
        if (current.length() > 0) {
            String lastChunk = current.toString().trim();
            if (!lastChunk.isEmpty()) {
                chunks.add(lastChunk);
            }
        }

        return chunks;
    }

    private String buildOverlap(List<String> sentences) {
        // Walk backwards through sentences, accumulating until we hit
        // the overlap character budget
        StringBuilder overlap = new StringBuilder();
        for (int i = sentences.size() - 1; i >= 0; i--) {
            String s = sentences.get(i);
            if (overlap.length() + s.length() + 1 > chunkOverlap) {
                break;
            }
            overlap.insert(0, s + " ");
        }
        return overlap.toString();
    }

    // ── Value type for indexed chunks ────────────────────────────────────

    public record IndexedChunk(int index, String text) {}
}
package com.medirag.diagnostic_service.repository;

import com.medirag.diagnostic_service.entity.MedicalKnowledgeChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface KnowledgeChunkRepository extends JpaRepository<MedicalKnowledgeChunk, Long> {

    /**
     * Cosine similarity search using pgvector's <=> operator.
     * <=> returns cosine DISTANCE (0 = identical, 2 = opposite) — so we
     * ORDER BY it ascending to get the most similar chunks first.
     *
     * The query vector is passed as a string in pgvector's text input
     * format: '[0.1,0.2,0.3,...]' — built by RetrievalService before calling this.
     */
    @Query(value = """
        SELECT * FROM diagnostic.knowledge_chunks
        ORDER BY embedding <=> CAST(:queryVector AS vector)
        LIMIT :topK
        """, nativeQuery = true)
    List<MedicalKnowledgeChunk> findSimilarChunks(
            @Param("queryVector") String queryVector,
            @Param("topK") int topK
    );

    /**
     * Same as above but filtered to a specific condition tag.
     * Useful later if you want to narrow retrieval — e.g. only search
     * "cardiomegaly"-tagged chunks when the AI's preliminary read suggests
     * cardiac involvement.
     */
    @Query(value = """
        SELECT * FROM diagnostic.knowledge_chunks
        WHERE condition_tag = :conditionTag
        ORDER BY embedding <=> CAST(:queryVector AS vector)
        LIMIT :topK
        """, nativeQuery = true)
    List<MedicalKnowledgeChunk> findSimilarChunksByCondition(
            @Param("queryVector") String queryVector,
            @Param("conditionTag") String conditionTag,
            @Param("topK") int topK
    );

    long countBySourceTitle(String sourceTitle);
}
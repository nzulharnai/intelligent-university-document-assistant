package com.university.docassistant.repository;

import com.university.docassistant.entity.DocumentChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

/**
 * Retrieval for Q&A. {@code query} is a to_tsquery expression of OR-ed words built by DocumentQnaService, so a
 * chunk matches if it shares any word with the question and ranks higher the more it shares. Uses the GIN index on
 * document_chunks.text_tsv; the 'russian' configuration must match DocumentSearchSchema.
 */
@Repository
public interface DocumentChunkRepository extends JpaRepository<DocumentChunk, Long> {

    /** A retrieved chunk with its document; {@code score} is 0 for chunks taken without a text match. */
    interface ChunkHit {
        Long getDocumentId();

        String getDocumentName();

        Integer getChunkIndex();

        Integer getStartOffset();

        Integer getEndOffset();

        String getContent();

        Double getScore();
    }

    @Query(value = """
            SELECT ch.document_id AS documentId, d.original_file_name AS documentName, ch.chunk_index AS chunkIndex,
                   ch.start_offset AS startOffset, ch.end_offset AS endOffset, ch.content AS content,
                   CAST(ts_rank_cd(ch.text_tsv, q) AS double precision) AS score
            FROM document_chunks ch
                JOIN documents d ON d.id = ch.document_id
                CROSS JOIN to_tsquery('russian', :query) q
            WHERE ch.document_id = :documentId AND ch.text_tsv @@ q
            ORDER BY score DESC, ch.chunk_index
            LIMIT :limit
            """, nativeQuery = true)
    List<ChunkHit> searchInDocument(@Param("documentId") Long documentId,
                                    @Param("query") String query,
                                    @Param("limit") int limit);

    /** The opening chunks of a document: the fallback context when no chunk shares a word with the question. */
    @Query(value = """
            SELECT ch.document_id AS documentId, d.original_file_name AS documentName, ch.chunk_index AS chunkIndex,
                   ch.start_offset AS startOffset, ch.end_offset AS endOffset, ch.content AS content,
                   CAST(0 AS double precision) AS score
            FROM document_chunks ch JOIN documents d ON d.id = ch.document_id
            WHERE ch.document_id = :documentId
            ORDER BY ch.chunk_index
            LIMIT :limit
            """, nativeQuery = true)
    List<ChunkHit> firstChunks(@Param("documentId") Long documentId, @Param("limit") int limit);

    /**
     * Best chunks among the documents of {@code courseIds}, which the caller takes from AccessControlService.scope
     * (pre-retrieval authorization): chunks of any other course never reach the prompt. Documents a student may not
     * see (DocumentRepository.VISIBLE_TO_STUDENTS_SQL: TEACHER_ONLY, AI or OCR still working or failed) are searched
     * only with {@code includeTeacherOnly}. {@code courseIds} must not be empty.
     */
    @Query(value = """
            SELECT ch.document_id AS documentId, d.original_file_name AS documentName, ch.chunk_index AS chunkIndex,
                   ch.start_offset AS startOffset, ch.end_offset AS endOffset, ch.content AS content,
                   CAST(ts_rank_cd(ch.text_tsv, q) AS double precision) AS score
            FROM document_chunks ch
                JOIN documents d ON d.id = ch.document_id
                CROSS JOIN to_tsquery('russian', :query) q
            WHERE d.course_id IN (:courseIds)
              AND (:includeTeacherOnly OR """ + DocumentRepository.VISIBLE_TO_STUDENTS_SQL + """
              )
              AND ch.text_tsv @@ q
            ORDER BY score DESC, d.id, ch.chunk_index
            LIMIT :limit
            """, nativeQuery = true)
    List<ChunkHit> searchInCourses(@Param("courseIds") Collection<Long> courseIds,
                                   @Param("includeTeacherOnly") boolean includeTeacherOnly,
                                   @Param("query") String query,
                                   @Param("limit") int limit);

    @Query(value = "SELECT COUNT(*) FROM document_chunks WHERE document_id = :documentId", nativeQuery = true)
    long countByDocumentId(@Param("documentId") Long documentId);
}

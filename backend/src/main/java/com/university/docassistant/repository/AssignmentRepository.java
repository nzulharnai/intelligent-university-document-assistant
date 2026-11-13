package com.university.docassistant.repository;

import com.university.docassistant.dto.AssignmentDtos.AssignmentResponse;
import com.university.docassistant.entity.Assignment;
import com.university.docassistant.entity.AssignmentStatus;
import java.util.Collection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Deleting assignments does not touch the students' tasks: callers remove those first with the matching
 * TaskRepository method. Assignments whose tasks carry student work are closed instead (AssignmentService).
 */
@Repository
public interface AssignmentRepository extends JpaRepository<Assignment, Long> {

    @Query(value = AssignmentResponse.SELECT + "WHERE c.id = :courseId",
            countQuery = "SELECT COUNT(a) FROM Assignment a WHERE a.course.id = :courseId")
    Page<AssignmentResponse> findViewsByCourseId(@Param("courseId") Long courseId, Pageable pageable);

    @Query(value = AssignmentResponse.SELECT + "WHERE d.id = :documentId",
            countQuery = "SELECT COUNT(a) FROM Assignment a WHERE a.sourceDocument.id = :documentId")
    Page<AssignmentResponse> findViewsBySourceDocumentId(@Param("documentId") Long documentId, Pageable pageable);

    @Query(AssignmentResponse.SELECT + "WHERE a.id = :id")
    Optional<AssignmentResponse> findViewById(@Param("id") Long id);

    @Query("SELECT a FROM Assignment a WHERE a.sourceDocument.id = :documentId")
    List<Assignment> findBySourceDocumentId(@Param("documentId") Long documentId);

    @Query("SELECT a FROM Assignment a WHERE a.sourceDocument.id IN :documentIds")
    List<Assignment> findBySourceDocumentIdIn(@Param("documentIds") Collection<Long> documentIds);

    /** What duplicate detection compares: every assignment of a course, open or closed. */
    record MatchInfo(Long id, String title, LocalDateTime deadline, AssignmentStatus status) {
    }

    @Query("""
            SELECT new com.university.docassistant.repository.AssignmentRepository$MatchInfo(a.id, a.title, a.deadline, a.status)
            FROM Assignment a WHERE a.course.id = :courseId
            """)
    List<MatchInfo> findMatchInfoByCourseId(@Param("courseId") Long courseId);

    /** [documentId, count] of assignments found in each of the documents, in one query. */
    @Query("""
            SELECT a.sourceDocument.id, COUNT(a) FROM Assignment a
            WHERE a.sourceDocument.id IN :documentIds
            GROUP BY a.sourceDocument.id
            """)
    List<Object[]> countBySourceDocumentIds(@Param("documentIds") Collection<Long> documentIds);

    @Query("SELECT COUNT(a) FROM Assignment a WHERE a.sourceDocument.id = :documentId")
    long countBySourceDocumentId(@Param("documentId") Long documentId);

    /** An ACTIVE assignment as the Q&A prompt lists it; {@code score} is the match of its title with the question. */
    interface QnaAssignment {
        Long getId();

        Long getCourseId();

        String getCourseTitle();

        String getTitle();

        LocalDateTime getDeadline();

        Integer getMaxScore();

        Boolean getAllowLate();

        Double getScore();
    }

    /**
     * Order of the Q&A block: titles sharing words with the question first (best match first), then upcoming
     * deadlines (nearest first), then assignments without a deadline, then past deadlines (latest first).
     * {@code query} is DocumentQnaService's to_tsquery expression, "" when the question has no words.
     */
    String QNA_ASSIGNMENTS_SELECT = """
            SELECT a.id AS id, a.course_id AS courseId, c.title AS courseTitle, a.title AS title,
                   a.deadline AS deadline, a.max_score AS maxScore, a.allow_late AS allowLate,
                   CAST(CASE WHEN :query = '' THEN 0
                        ELSE ts_rank_cd(to_tsvector('russian', a.title), to_tsquery('russian', :query)) END
                        AS double precision) AS score
            FROM assignments a JOIN courses c ON c.id = a.course_id
            WHERE a.status = 'ACTIVE' AND
            """;

    String QNA_ASSIGNMENTS_ORDER = """
            ORDER BY score DESC,
                     CASE WHEN a.deadline >= :now THEN 0 WHEN a.deadline IS NULL THEN 1 ELSE 2 END,
                     CASE WHEN a.deadline >= :now THEN a.deadline END ASC,
                     a.deadline DESC, a.id
            LIMIT :limit
            """;

    /**
     * ACTIVE assignments of {@code courseIds} for the Q&A prompt. The caller takes the ids from
     * AccessControlService.scope (or an authorized course), so assignments of other courses never reach the prompt.
     * {@code courseIds} must not be empty.
     */
    @Query(value = QNA_ASSIGNMENTS_SELECT + "a.course_id IN (:courseIds)\n" + QNA_ASSIGNMENTS_ORDER, nativeQuery = true)
    List<QnaAssignment> findForQnaInCourses(@Param("courseIds") Collection<Long> courseIds,
                                            @Param("query") String query,
                                            @Param("now") LocalDateTime now,
                                            @Param("limit") int limit);

    /** ACTIVE assignments found in one document, for a question about that document. */
    @Query(value = QNA_ASSIGNMENTS_SELECT + "a.source_document_id = :documentId\n" + QNA_ASSIGNMENTS_ORDER,
            nativeQuery = true)
    List<QnaAssignment> findForQnaInDocument(@Param("documentId") Long documentId,
                                             @Param("query") String query,
                                             @Param("now") LocalDateTime now,
                                             @Param("limit") int limit);

    @Query("SELECT a.course.id FROM Assignment a WHERE a.id = :id")
    Optional<Long> findCourseIdById(@Param("id") Long id);

    @Modifying
    @Query("DELETE FROM Assignment a WHERE a.sourceDocument.id = :documentId")
    int deleteBySourceDocumentId(@Param("documentId") Long documentId);

    @Modifying
    @Query("DELETE FROM Assignment a WHERE a.sourceDocument.id IN (SELECT d.id FROM Document d WHERE d.owner.id = :ownerId)")
    int deleteBySourceDocumentOwnerId(@Param("ownerId") Long ownerId);
}

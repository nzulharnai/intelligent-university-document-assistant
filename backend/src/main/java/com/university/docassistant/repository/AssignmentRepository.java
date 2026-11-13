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

    @Query("SELECT a.course.id FROM Assignment a WHERE a.id = :id")
    Optional<Long> findCourseIdById(@Param("id") Long id);

    @Modifying
    @Query("DELETE FROM Assignment a WHERE a.sourceDocument.id = :documentId")
    int deleteBySourceDocumentId(@Param("documentId") Long documentId);

    @Modifying
    @Query("DELETE FROM Assignment a WHERE a.sourceDocument.id IN (SELECT d.id FROM Document d WHERE d.owner.id = :ownerId)")
    int deleteBySourceDocumentOwnerId(@Param("ownerId") Long ownerId);
}

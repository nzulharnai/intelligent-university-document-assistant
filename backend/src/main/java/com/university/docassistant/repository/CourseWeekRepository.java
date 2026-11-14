package com.university.docassistant.repository;

import com.university.docassistant.entity.CourseWeek;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** US-16: the week plans of courses (course_weeks). */
@Repository
public interface CourseWeekRepository extends JpaRepository<CourseWeek, Long> {

    @Query("SELECT w FROM CourseWeek w WHERE w.course.id = :courseId ORDER BY w.weekNumber")
    List<CourseWeek> findByCourseId(@Param("courseId") Long courseId);

    @Query("SELECT w FROM CourseWeek w WHERE w.course.id = :courseId AND w.weekNumber = :week")
    Optional<CourseWeek> findByCourseIdAndWeek(@Param("courseId") Long courseId, @Param("week") int week);

    /**
     * A week topic from a syllabus plan: inserted, or replacing the topic of a week the teacher has not edited.
     * Atomic, so two syllabi analyzed at once never break the (course_id, week_number) key. 1 when written.
     */
    @Modifying
    @Query(value = """
            INSERT INTO course_weeks (course_id, week_number, topic, source_document_id, edited_by_teacher)
            VALUES (:courseId, :week, :topic, :documentId, false)
            ON CONFLICT (course_id, week_number) DO UPDATE
                SET topic = EXCLUDED.topic, source_document_id = EXCLUDED.source_document_id
                WHERE course_weeks.edited_by_teacher = false
            """, nativeQuery = true)
    int upsertFromPlan(@Param("courseId") Long courseId, @Param("week") int week, @Param("topic") String topic,
                       @Param("documentId") Long documentId);

    /** Topics past a lowered weeks count go with their weeks. */
    @Modifying
    @Query("DELETE FROM CourseWeek w WHERE w.course.id = :courseId AND w.weekNumber > :weeksCount")
    int deleteAbove(@Param("courseId") Long courseId, @Param("weeksCount") int weeksCount);
}

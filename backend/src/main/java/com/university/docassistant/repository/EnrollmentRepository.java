package com.university.docassistant.repository;

import com.university.docassistant.entity.Enrollment;
import com.university.docassistant.entity.Enrollment.EnrollmentId;
import com.university.docassistant.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface EnrollmentRepository extends JpaRepository<Enrollment, EnrollmentId> {

    @Query("""
            SELECT COUNT(e) > 0 FROM Enrollment e
            WHERE e.id.studentId = :studentId AND e.id.courseId = :courseId
            """)
    boolean existsByStudentIdAndCourseId(@Param("studentId") Long studentId, @Param("courseId") Long courseId);

    @Query("SELECT e.id.courseId FROM Enrollment e WHERE e.id.studentId = :studentId")
    List<Long> findCourseIdsByStudentId(@Param("studentId") Long studentId);

    @Query("SELECT COUNT(e) FROM Enrollment e WHERE e.id.courseId = :courseId")
    long countByCourseId(@Param("courseId") Long courseId);

    /** The course's students, by id only: who gets the course's notifications. */
    @Query("SELECT e.id.studentId FROM Enrollment e WHERE e.id.courseId = :courseId")
    List<Long> findStudentIdsByCourseId(@Param("courseId") Long courseId);

    // Selected from User, so the page's sort (fullName, email) applies to the student.
    @Query(value = "SELECT s FROM User s WHERE s.id IN (SELECT e.id.studentId FROM Enrollment e WHERE e.id.courseId = :courseId)",
            countQuery = "SELECT COUNT(e) FROM Enrollment e WHERE e.id.courseId = :courseId")
    Page<User> findStudentsByCourseId(@Param("courseId") Long courseId, Pageable pageable);

    /** Adds one enrollment without loading the course's other students; a repeat call is a no-op (returns 0). */
    @Modifying
    @Query(value = """
            INSERT INTO course_students (course_id, student_id, enrolled_at) VALUES (:courseId, :studentId, now())
            ON CONFLICT DO NOTHING
            """, nativeQuery = true)
    int enroll(@Param("courseId") Long courseId, @Param("studentId") Long studentId);

    @Modifying
    @Query("DELETE FROM Enrollment e WHERE e.id.courseId = :courseId AND e.id.studentId = :studentId")
    int unenroll(@Param("courseId") Long courseId, @Param("studentId") Long studentId);

    @Modifying
    @Query("DELETE FROM Enrollment e WHERE e.id.studentId = :studentId")
    int deleteByStudentId(@Param("studentId") Long studentId);
}

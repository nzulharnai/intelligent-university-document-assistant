package com.university.docassistant.repository;

import com.university.docassistant.entity.Course;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** Enrollments live in {@link EnrollmentRepository}. */
@Repository
public interface CourseRepository extends JpaRepository<Course, Long> {

    @EntityGraph(attributePaths = "teacher")
    @Query(value = "SELECT c FROM Course c", countQuery = "SELECT COUNT(c) FROM Course c")
    Page<Course> findAllWithTeacher(Pageable pageable);

    @EntityGraph(attributePaths = "teacher")
    Page<Course> findByTeacherId(Long teacherId, Pageable pageable);

    /**
     * JPQL condition on Course c: the student is enrolled in it. The student queries select from Course, so the page's
     * sort (title, code, id) applies to the course; selected from Enrollment it became "order by e.title" and failed.
     */
    String ENROLLED_STUDENT = "(c.id IN (SELECT e.id.courseId FROM Enrollment e WHERE e.id.studentId = :studentId))";

    @EntityGraph(attributePaths = "teacher")
    @Query(value = "SELECT c FROM Course c WHERE " + ENROLLED_STUDENT,
            countQuery = "SELECT COUNT(c) FROM Course c WHERE " + ENROLLED_STUDENT)
    Page<Course> findByStudentId(@Param("studentId") Long studentId, Pageable pageable);

    @EntityGraph(attributePaths = "teacher")
    Optional<Course> findWithTeacherById(Long id);

    // Title or code contains the pattern (LikePatterns.contains: lower-case, % and _ escaped), per role's scope.

    @EntityGraph(attributePaths = "teacher")
    @Query(value = """
            SELECT c FROM Course c
            WHERE LOWER(c.title) LIKE :pattern ESCAPE '\\' OR LOWER(COALESCE(c.code, '')) LIKE :pattern ESCAPE '\\'
            """,
            countQuery = """
            SELECT COUNT(c) FROM Course c
            WHERE LOWER(c.title) LIKE :pattern ESCAPE '\\' OR LOWER(COALESCE(c.code, '')) LIKE :pattern ESCAPE '\\'
            """)
    Page<Course> searchAll(@Param("pattern") String pattern, Pageable pageable);

    @EntityGraph(attributePaths = "teacher")
    @Query(value = """
            SELECT c FROM Course c
            WHERE c.teacher.id = :teacherId
              AND (LOWER(c.title) LIKE :pattern ESCAPE '\\' OR LOWER(COALESCE(c.code, '')) LIKE :pattern ESCAPE '\\')
            """,
            countQuery = """
            SELECT COUNT(c) FROM Course c
            WHERE c.teacher.id = :teacherId
              AND (LOWER(c.title) LIKE :pattern ESCAPE '\\' OR LOWER(COALESCE(c.code, '')) LIKE :pattern ESCAPE '\\')
            """)
    Page<Course> searchByTeacherId(@Param("teacherId") Long teacherId, @Param("pattern") String pattern,
                                   Pageable pageable);

    @EntityGraph(attributePaths = "teacher")
    @Query(value = """
            SELECT c FROM Course c
            WHERE """ + ENROLLED_STUDENT + """
              AND (LOWER(c.title) LIKE :pattern ESCAPE '\\' OR LOWER(COALESCE(c.code, '')) LIKE :pattern ESCAPE '\\')
            """,
            countQuery = """
            SELECT COUNT(c) FROM Course c
            WHERE """ + ENROLLED_STUDENT + """
              AND (LOWER(c.title) LIKE :pattern ESCAPE '\\' OR LOWER(COALESCE(c.code, '')) LIKE :pattern ESCAPE '\\')
            """)
    Page<Course> searchByStudentId(@Param("studentId") Long studentId, @Param("pattern") String pattern,
                                   Pageable pageable);

    /** The course behind an invitation link; codes are unique. */
    Optional<Course> findByInviteCode(String inviteCode);

    @Query("SELECT c.id FROM Course c WHERE c.teacher.id = :teacherId")
    List<Long> findIdsByTeacherId(@Param("teacherId") Long teacherId);

    @Query("SELECT c.id FROM Course c")
    List<Long> findAllIds();

    @Modifying
    @Query("UPDATE Course c SET c.teacher = NULL WHERE c.teacher.id = :teacherId")
    int detachTeacher(@Param("teacherId") Long teacherId);
}

package com.university.docassistant.entity;

import com.university.docassistant.util.InviteCodes;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;

/**
 * A course is the unit of access: its teacher manages it and its documents, its students (see {@link Enrollment})
 * can read them, ADMIN can do everything.
 */
@Entity
@Table(name = "courses", indexes = {
        @Index(name = "idx_courses_code", columnList = "code"),
        @Index(name = "idx_courses_teacher_id", columnList = "teacher_id")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Course {

    public static final int DEFAULT_WEEKS = 15;
    public static final int MAX_WEEKS = 30;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    @Column(length = 50)
    private String code;

    @Column(columnDefinition = "TEXT")
    private String description;

    /**
     * Secret part of the invitation link /join/{code}: the only way a student joins the course. Never sent to
     * students. NOT NULL and unique in the database (CourseInviteCodeMigration; ddl-auto alone cannot add a NOT NULL
     * column to a table with rows), so it is not marked nullable=false here.
     */
    @Column(name = "invite_code", length = 64)
    private String inviteCode;

    /**
     * US-16: how many weeks the course is split into, 1..{@value #MAX_WEEKS}. A syllabus plan with more weeks raises
     * it (CourseWeekPlanService); the teacher changes it with PUT /courses/{id}/weeks.
     */
    @Column(name = "weeks_count", nullable = false)
    @ColumnDefault("15")
    @Builder.Default
    private int weeksCount = DEFAULT_WEEKS;

    /** Null once the teacher's account is deleted. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "teacher_id")
    private User teacher;

    @PrePersist
    protected void onCreate() {
        if (inviteCode == null) {
            inviteCode = InviteCodes.generate();
        }
    }
}

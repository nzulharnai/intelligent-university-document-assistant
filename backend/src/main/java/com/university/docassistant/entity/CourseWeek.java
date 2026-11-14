package com.university.docassistant.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/**
 * US-16: one week of a course's plan - its topic, from the syllabus the AI read ({@code sourceDocument}) or typed by
 * the teacher. A row exists only for weeks with a topic or a teacher's edit; weeks 1..Course.weeksCount without a row
 * simply have no topic. Deleted with the course; the plan stays when its syllabus is deleted.
 */
@Entity
@Table(name = "course_weeks", uniqueConstraints = @UniqueConstraint(name = "uk_course_weeks_course_week",
        columnNames = {"course_id", "week_number"}))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CourseWeek {

    public static final int MAX_TOPIC_LENGTH = 300;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "course_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Course course;

    @Column(name = "week_number", nullable = false)
    private int weekNumber;

    @Column(length = MAX_TOPIC_LENGTH)
    private String topic;

    /** The syllabus the topic came from; null for a teacher's topic or once that document is deleted. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_document_id")
    @OnDelete(action = OnDeleteAction.SET_NULL)
    private Document sourceDocument;

    /** The teacher set or cleared the topic: a later syllabus plan never overwrites it. */
    @Column(name = "edited_by_teacher", nullable = false)
    @ColumnDefault("false")
    private boolean editedByTeacher;
}

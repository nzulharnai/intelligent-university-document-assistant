package com.university.docassistant.dto;

import com.university.docassistant.entity.Course;
import com.university.docassistant.entity.CourseWeek;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/** US-16: course weeks. */
public final class CourseWeekDtos {

    private CourseWeekDtos() {
    }

    /**
     * GET /courses/{id}/weeks. A student gets only the weeks that are not hidden and have documents they can see or
     * assignments, and the document counts cover only what they can see; {@code pendingSuggestions} is null for them.
     * The teacher gets every week 1..weeksCount, hidden ones marked. {@code withoutWeekAssignmentCount}: the
     * assignments in "Без недели".
     */
    public record WeeksOverview(
            Long courseId,
            int weeksCount,
            List<WeekView> weeks,
            long withoutWeekCount,
            Long pendingSuggestions,
            long withoutWeekAssignmentCount
    ) {
        public WeeksOverview(Long courseId, int weeksCount, List<WeekView> weeks, long withoutWeekCount,
                             Long pendingSuggestions) {
            this(courseId, weeksCount, weeks, withoutWeekCount, pendingSuggestions, 0);
        }
    }

    /**
     * {@code topic} null when the week has none; {@code editedByTeacher} and {@code hidden} only for the teacher (null
     * for a student, who never gets a hidden week). {@code assignmentCount}: the course's assignments in the week.
     */
    public record WeekView(int week, String topic, long documentCount, Boolean editedByTeacher, long assignmentCount,
                           Boolean hidden) {
        public WeekView(int week, String topic, long documentCount, Boolean editedByTeacher) {
            this(week, topic, documentCount, editedByTeacher, 0, null);
        }
    }

    /** US-16 AC-4: PUT /courses/{id}/weeks/{week}/visibility; true hides the week and its documents from students. */
    public record UpdateWeekVisibilityRequest(@NotNull Boolean hidden) {
    }

    public record UpdateWeeksCountRequest(
            @NotNull @Min(1) @Max(Course.MAX_WEEKS) Integer weeksCount
    ) {
    }

    /** A blank or null topic clears it. */
    public record UpdateWeekTopicRequest(
            @Size(max = CourseWeek.MAX_TOPIC_LENGTH) String topic
    ) {
    }

    /** {@code week} null takes the document out of its week ("Без недели"). */
    public record SetDocumentWeekRequest(Integer week) {
    }

    /** The document's week after a change; the suggestion is always cleared by then. */
    public record DocumentWeekResponse(Long documentId, Integer weekNumber, Integer suggestedWeek) {
    }

    public record AcceptAllResponse(int accepted) {
    }

    /** POST /courses/{id}/weeks/suggest: how many documents were queued for a hint. */
    public record SuggestResponse(int documents) {
    }
}

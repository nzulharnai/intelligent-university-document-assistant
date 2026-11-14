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
     * GET /courses/{id}/weeks. A student gets only the weeks with documents they can see, and their counts cover
     * only those; {@code pendingSuggestions} is null for them. The teacher gets every week 1..weeksCount.
     */
    public record WeeksOverview(
            Long courseId,
            int weeksCount,
            List<WeekView> weeks,
            long withoutWeekCount,
            Long pendingSuggestions
    ) {
    }

    /** {@code topic} null when the week has none; {@code editedByTeacher} only for the teacher (null for a student). */
    public record WeekView(int week, String topic, long documentCount, Boolean editedByTeacher) {
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

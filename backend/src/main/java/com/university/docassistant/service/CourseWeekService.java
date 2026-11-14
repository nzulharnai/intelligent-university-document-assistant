package com.university.docassistant.service;

import com.university.docassistant.dto.CourseWeekDtos.AcceptAllResponse;
import com.university.docassistant.dto.CourseWeekDtos.DocumentWeekResponse;
import com.university.docassistant.dto.CourseWeekDtos.SuggestResponse;
import com.university.docassistant.dto.CourseWeekDtos.WeekView;
import com.university.docassistant.dto.CourseWeekDtos.WeeksOverview;
import com.university.docassistant.entity.Course;
import com.university.docassistant.entity.CourseWeek;
import com.university.docassistant.entity.Document;
import com.university.docassistant.entity.Role;
import com.university.docassistant.entity.User;
import com.university.docassistant.exception.ApiException;
import com.university.docassistant.exception.ErrorCodes;
import com.university.docassistant.repository.CourseWeekRepository;
import com.university.docassistant.repository.DocumentRepository;
import com.university.docassistant.security.AccessControlService;
import com.university.docassistant.security.Permission;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * US-16: the weeks of a course and the week of each document. Reading is for the course's members; every change is
 * MANAGE_COURSE (the course teacher or ADMIN), checked through AccessControlService before anything is read.
 * A student sees only confirmed weeks and only through documents they may see; the AI's hints never reach them.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CourseWeekService {

    private final AccessControlService access;
    private final DocumentRepository documentRepository;
    private final CourseWeekRepository weekRepository;
    private final WeekSuggestionService suggestionService;
    private final AiClientService aiClient;
    private final AuditService auditService;

    /** "?week=3" or a week in a request: 1..{@value Course#MAX_WEEKS}, else 400 WEEK_OUT_OF_RANGE. */
    public static int parseWeek(String value) {
        if (value == null || !value.matches("\\d{1,2}")) {
            throw outOfRange(Course.MAX_WEEKS);
        }
        int week = Integer.parseInt(value);
        if (week < 1 || week > Course.MAX_WEEKS) {
            throw outOfRange(Course.MAX_WEEKS);
        }
        return week;
    }

    @Transactional(readOnly = true)
    public WeeksOverview weeks(Long courseId, String email) {
        User user = access.currentUser(email);
        Course course = access.authorizeCourse(user, courseId, Permission.VIEW_COURSE);
        return overview(course, user.getRole() != Role.ROLE_STUDENT);
    }

    /** PUT /courses/{id}/weeks: below a week a document is in, 409 WEEK_IN_USE. Topics and hints past it go. */
    @Transactional
    public WeeksOverview updateWeeksCount(Long courseId, int weeksCount, String email) {
        User user = access.currentUser(email);
        Course course = access.authorizeCourse(user, courseId, Permission.MANAGE_COURSE);
        if (weeksCount < 1 || weeksCount > Course.MAX_WEEKS) {
            throw outOfRange(Course.MAX_WEEKS);
        }
        Integer used = documentRepository.findMaxWeek(courseId);
        if (used != null && weeksCount < used) {
            throw ApiException.conflict(ErrorCodes.WEEK_IN_USE, "Week " + used + " has documents");
        }
        int previous = course.getWeeksCount();
        course.setWeeksCount(weeksCount);
        weekRepository.deleteAbove(courseId, weeksCount);
        documentRepository.clearSuggestionsAbove(courseId, weeksCount);
        if (previous != weeksCount) {
            auditService.record(user.getEmail(), AuditService.COURSE_WEEKS_CHANGED,
                    "Course " + courseId + ": weeks count " + previous + " -> " + weeksCount);
        }
        return overview(course, true);
    }

    /** PUT /courses/{id}/weeks/{week}: sets or (blank) clears the topic; a later syllabus never overwrites it. */
    @Transactional
    public WeekView setTopic(Long courseId, int week, String topic, String email) {
        User user = access.currentUser(email);
        Course course = access.authorizeCourse(user, courseId, Permission.MANAGE_COURSE);
        requireWeek(week, course.getWeeksCount());
        String value = topic == null || topic.isBlank() ? null : topic.replaceAll("\\s+", " ").strip();
        if (value != null && value.length() > CourseWeek.MAX_TOPIC_LENGTH) {
            value = value.substring(0, CourseWeek.MAX_TOPIC_LENGTH);
        }
        CourseWeek row = weekRepository.findByCourseIdAndWeek(courseId, week)
                .orElseGet(() -> CourseWeek.builder().course(course).weekNumber(week).build());
        row.setTopic(value);
        row.setSourceDocument(null);
        row.setEditedByTeacher(true);
        weekRepository.save(row);
        auditService.record(user.getEmail(), AuditService.COURSE_WEEKS_CHANGED, "Course " + courseId + ": week "
                + week + (value == null ? " topic cleared" : " topic set to \"" + value + "\""));
        long documents = counts(courseId, true).getOrDefault(week, 0L);
        return new WeekView(week, value, documents, true);
    }

    /** PATCH /documents/{id}/week: a week (1..weeks count) or null for "Без недели"; the hint is cleared. */
    @Transactional
    public DocumentWeekResponse setDocumentWeek(Long documentId, Integer week, String email) {
        User user = access.currentUser(email);
        Document document = access.authorizeDocument(user, documentId, Permission.MANAGE_COURSE);
        int weeksCount = weeksCountOf(document);
        if (week != null) {
            requireWeek(week, weeksCount);
        }
        documentRepository.setWeek(documentId, week);
        auditService.record(user.getEmail(), AuditService.DOCUMENT_WEEK_SET, "Document " + documentId + " \""
                + document.getOriginalFileName() + "\": " + (week == null ? "week cleared" : "week " + week)
                + " (by hand" + (document.getSuggestedWeek() == null ? "" : ", AI suggested week "
                + document.getSuggestedWeek()) + ")");
        return new DocumentWeekResponse(documentId, week, null);
    }

    /** POST /documents/{id}/week/accept: the hint becomes the week; 409 WEEK_NO_SUGGESTION without one. */
    @Transactional
    public DocumentWeekResponse acceptSuggestion(Long documentId, String email) {
        User user = access.currentUser(email);
        Document document = access.authorizeDocument(user, documentId, Permission.MANAGE_COURSE);
        Integer suggested = document.getSuggestedWeek();
        if (suggested == null || document.getWeekNumber() != null) {
            throw ApiException.conflict(ErrorCodes.WEEK_NO_SUGGESTION, "The document has no week suggestion");
        }
        requireWeek(suggested, weeksCountOf(document));
        documentRepository.setWeek(documentId, suggested);
        auditService.record(user.getEmail(), AuditService.DOCUMENT_WEEK_SET, "Document " + documentId + " \""
                + document.getOriginalFileName() + "\": week " + suggested + " (AI suggestion accepted)");
        return new DocumentWeekResponse(documentId, suggested, null);
    }

    /** POST /courses/{id}/weeks/accept-all: every open hint of the course within its weeks; how many. */
    @Transactional
    public AcceptAllResponse acceptAll(Long courseId, String email) {
        User user = access.currentUser(email);
        Course course = access.authorizeCourse(user, courseId, Permission.MANAGE_COURSE);
        List<Object[]> pending = documentRepository.findPendingSuggestions(courseId, course.getWeeksCount());
        int accepted = documentRepository.acceptAllSuggestions(courseId, course.getWeeksCount());
        if (accepted > 0) {
            String moves = pending.stream().map(row -> row[0] + " -> " + row[1]).collect(Collectors.joining(", "));
            auditService.record(user.getEmail(), AuditService.DOCUMENT_WEEK_SET, "Course " + courseId + ": "
                    + accepted + " AI week suggestion(s) accepted (document -> week): " + moves);
        }
        return new AcceptAllResponse(accepted);
    }

    /**
     * POST /courses/{id}/weeks/suggest: hints for the course's documents without a week and without a hint, in the
     * background. 503 AI_NOT_CONFIGURED without the AI, AI_QUEUE_FULL when its queue is full.
     */
    @Transactional(readOnly = true)
    public SuggestResponse requestSuggestions(Long courseId, String email) {
        User user = access.currentUser(email);
        access.authorizeCourse(user, courseId, Permission.MANAGE_COURSE);
        if (!aiClient.isConfigured()) {
            throw ApiException.of(HttpStatus.SERVICE_UNAVAILABLE, ErrorCodes.AI_NOT_CONFIGURED,
                    "AI analysis is not configured");
        }
        int documents = documentRepository.findIdsWithoutWeek(courseId, WeekSuggestionService.SUGGESTED).size();
        if (documents > 0) {
            try {
                suggestionService.suggestForCourseAsync(courseId);
            } catch (TaskRejectedException ex) {
                throw ApiException.of(HttpStatus.SERVICE_UNAVAILABLE, ErrorCodes.AI_QUEUE_FULL,
                        "AI analysis queue is full, try again later");
            }
            log.info("User {} asked for week suggestions in course {}: {} document(s)", user.getEmail(), courseId,
                    documents);
        }
        return new SuggestResponse(documents);
    }

    private WeeksOverview overview(Course course, boolean manages) {
        Long courseId = course.getId();
        Map<Integer, Long> counts = counts(courseId, manages);
        Map<Integer, CourseWeek> topics = weekRepository.findByCourseId(courseId).stream()
                .collect(Collectors.toMap(CourseWeek::getWeekNumber, week -> week));
        List<WeekView> weeks = new ArrayList<>();
        for (int week = 1; week <= course.getWeeksCount(); week++) {
            long documents = counts.getOrDefault(week, 0L);
            CourseWeek row = topics.get(week);
            if (manages) {
                weeks.add(new WeekView(week, row == null ? null : row.getTopic(), documents,
                        row != null && row.isEditedByTeacher()));
            } else if (documents > 0) {
                // A student sees a week only through documents they can see; its topic may come from a hidden syllabus.
                weeks.add(new WeekView(week, row == null ? null : row.getTopic(), documents, null));
            }
        }
        long withoutWeek = counts.getOrDefault(null, 0L);
        Long pending = manages ? documentRepository.countPendingSuggestions(courseId) : null;
        return new WeeksOverview(courseId, course.getWeeksCount(), weeks, withoutWeek, pending);
    }

    /** Documents per week (null key: without a week); a student's only what they may see. */
    private Map<Integer, Long> counts(Long courseId, boolean manages) {
        Map<Integer, Long> counts = new HashMap<>();
        for (Object[] row : documentRepository.countByWeek(courseId, manages)) {
            Integer week = row[0] == null ? null : ((Number) row[0]).intValue();
            counts.put(week, ((Number) row[1]).longValue());
        }
        return counts;
    }

    private static int weeksCountOf(Document document) {
        if (document.getCourse() == null) {
            throw ApiException.badRequest(ErrorCodes.WEEK_OUT_OF_RANGE, "The document has no course");
        }
        return document.getCourse().getWeeksCount();
    }

    private static void requireWeek(int week, int weeksCount) {
        if (week < 1 || week > weeksCount) {
            throw outOfRange(weeksCount);
        }
    }

    private static ApiException outOfRange(int weeksCount) {
        return ApiException.badRequest(ErrorCodes.WEEK_OUT_OF_RANGE, "Week must be from 1 to " + weeksCount);
    }
}

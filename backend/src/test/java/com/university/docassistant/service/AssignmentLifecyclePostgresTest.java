package com.university.docassistant.service;

import com.university.docassistant.config.AssignmentStatusMigration;
import com.university.docassistant.config.DocumentSearchSchema;
import com.university.docassistant.config.JpaSettings;
import com.university.docassistant.config.PrivacyProperties;
import com.university.docassistant.dto.AssignmentDtos.AssignmentResponse;
import com.university.docassistant.dto.AssignmentDtos.UpdateAssignmentRequest;
import com.university.docassistant.dto.TaskExtractDto;
import com.university.docassistant.entity.AiStatus;
import com.university.docassistant.entity.Assignment;
import com.university.docassistant.entity.AssignmentStatus;
import com.university.docassistant.entity.Course;
import com.university.docassistant.entity.Document;
import com.university.docassistant.entity.DocumentChunk;
import com.university.docassistant.entity.DocumentCategory;
import com.university.docassistant.entity.DocumentType;
import com.university.docassistant.entity.DocumentVisibility;
import com.university.docassistant.entity.OcrStatus;
import com.university.docassistant.entity.Role;
import com.university.docassistant.entity.Task;
import com.university.docassistant.entity.TaskStatus;
import com.university.docassistant.entity.TaskSubmission;
import com.university.docassistant.entity.User;
import com.university.docassistant.exception.ApiException;
import com.university.docassistant.exception.ErrorCodes;
import com.university.docassistant.repository.AssignmentRepository;
import com.university.docassistant.repository.DocumentChunkRepository;
import com.university.docassistant.repository.DocumentRepository;
import com.university.docassistant.repository.DocumentSummary;
import com.university.docassistant.repository.EnrollmentRepository;
import com.university.docassistant.repository.TaskRepository;
import com.university.docassistant.repository.TaskSubmissionRepository;
import com.university.docassistant.security.AccessControlService;
import com.university.docassistant.security.Permission;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.when;

/**
 * The same guarantees as {@link AssignmentLifecycleTest} and {@link AssignmentDraftTest}, against the real queries (native fanOut, JPQL activity and
 * enrollment filters) on PostgreSQL. Opt-in, so the normal build needs no database:
 * <pre>
 * mvnw.cmd test "-Dtest=*PostgresTest" "-Dsurefire.failIfNoSpecifiedTests=false"
 *     "-Dit.db.url=jdbc:postgresql://localhost:5432/doc_assistant_test" "-Dit.db.user=postgres" "-Dit.db.password=..."
 * </pre>
 * Only ever the separate doc_assistant_test database (README "Тесты"), never doc_assistant_db: the schema is created
 * and dropped (ddl-auto=create-drop).
 */
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfSystemProperty(named = "it.db.url", matches = ".+")
@Import({JpaSettings.class, AssignmentService.class, AssignmentEvents.class, AuditService.class, DocumentPrivacyService.class})
@EnableConfigurationProperties(PrivacyProperties.class)
class AssignmentLifecyclePostgresTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getProperty("it.db.url"));
        registry.add("spring.datasource.username", () -> System.getProperty("it.db.user", "postgres"));
        registry.add("spring.datasource.password", () -> System.getProperty("it.db.password", ""));
    }

    @MockBean
    private AccessControlService access;
    @MockBean
    private NotificationService notifications;

    @Autowired
    private AssignmentService service;
    @Autowired
    private AssignmentRepository assignments;
    @Autowired
    private TaskRepository tasks;
    @Autowired
    private TaskSubmissionRepository submissions;
    @Autowired
    private EnrollmentRepository enrollments;
    @Autowired
    private DocumentRepository documents;
    @Autowired
    private DocumentChunkRepository chunks;
    @Autowired
    private EntityManager em;
    @Autowired
    private JdbcTemplate jdbc;

    private User teacher;
    private User student;
    private Course course;
    private Document syllabus;

    @BeforeEach
    void setUp() {
        teacher = persist(User.builder().email("t@uni.kz").fullName("Teacher").role(Role.ROLE_TEACHER)
                .createdAt(LocalDateTime.now()).build());
        student = persist(User.builder().email("s@uni.kz").fullName("Student").role(Role.ROLE_STUDENT)
                .createdAt(LocalDateTime.now()).build());
        course = persist(Course.builder().title("Algorithms").code("ALG").teacher(teacher).build());
        syllabus = persist(Document.builder().originalFileName("syllabus.pdf")
                .storedFileName(UUID.randomUUID().toString()).contentType("application/pdf").fileSize(1L)
                .category(DocumentCategory.LECTURE).type(DocumentType.OTHER).aiStatus(AiStatus.DONE)
                .visibility(DocumentVisibility.COURSE_STUDENTS).course(course).owner(teacher)
                .uploadedAt(LocalDateTime.now()).build());
        enrollments.enroll(course.getId(), student.getId());
        when(access.currentUser("t@uni.kz")).thenReturn(teacher);
        when(access.authorizeCourse(teacher, course.getId(), Permission.MANAGE_ASSIGNMENTS)).thenReturn(course);
    }

    @Test
    void reEnrollGivesBackTheSameTasksWithoutDuplicates() {
        Assignment lab = fromSyllabus("Lab 1");
        service.handOut(course.getId());
        Task task = onlyTaskOf(lab);
        workOn(task);

        enrollments.unenroll(course.getId(), student.getId());
        flushAndClear();
        assertThat(tasks.findById(task.getId())).as("kept after unenroll").isPresent();
        assertThat(submissions.count()).isEqualTo(1);
        assertThat(tasks.findViewsByUserId(student.getId(), PageRequest.of(0, 10)).getContent())
                .as("hidden from the student while not enrolled").isEmpty();

        enrollments.enroll(course.getId(), student.getId());
        assertThat(service.handOut(course.getId())).as("nothing new created").isZero();
        flushAndClear();

        assertThat(tasks.findAll()).extracting(Task::getId).containsExactly(task.getId());
        assertThat(tasks.findViewsByUserId(student.getId(), PageRequest.of(0, 10)).getContent())
                .extracting(view -> view.id()).containsExactly(task.getId());
    }

    @Test
    void closedAssignmentsAreNotHandedOutToNewStudents() {
        Assignment open = fromSyllabus("Lab 1");
        Assignment closed = fromSyllabus("Lab 0");
        closed.setStatus(AssignmentStatus.CLOSED);
        flushAndClear();

        service.handOut(course.getId());

        assertThat(tasks.findAll()).extracting(t -> t.getAssignment().getId()).containsExactly(open.getId());
    }

    @Test
    void rewordedReAnalysisClosesWorkedOnAssignmentsAndDeletesUntouchedOnes() {
        Assignment worked = fromSyllabus("Lab 1");
        Assignment untouched = fromSyllabus("Lab 2");
        service.handOut(course.getId());
        workOn(onlyTaskOf(worked));
        flushAndClear();

        service.replaceFromDocument(syllabus.getId(), course.getId(),
                List.of(new TaskExtractDto("Laboratory work No. 1", "Same lab, new wording", null)));
        flushAndClear();

        Assignment kept = assignments.findById(worked.getId()).orElseThrow();
        assertThat(kept.getStatus()).isEqualTo(AssignmentStatus.CLOSED);
        assertThat(tasks.findAll()).extracting(t -> t.getAssignment().getId()).contains(worked.getId());
        assertThat(submissions.count()).isEqualTo(1);
        assertThat(assignments.findById(untouched.getId())).as("no activity: removed as before").isEmpty();
        assertThat(assignments.findBySourceDocumentId(syllabus.getId())).extracting(Assignment::getTitle)
                .containsExactlyInAnyOrder("Lab 1", "Laboratory work No. 1");
    }

    @Test
    void deletingTheDocumentKeepsWorkedOnAssignmentsClosedAndUnlinked() {
        Assignment worked = fromSyllabus("Lab 1");
        Assignment untouched = fromSyllabus("Lab 2");
        service.handOut(course.getId());
        workOn(onlyTaskOf(worked));
        // An old task without an assignment that points straight at the document, completed by the student.
        Task legacy = persist(Task.builder().title("Old task").status(TaskStatus.COMPLETED).document(syllabus)
                .course(course).user(student).build());
        flushAndClear();

        // What DocumentService.delete does, in the same order.
        service.releaseDocuments(List.of(syllabus.getId()), "t@uni.kz");
        tasks.deleteByAssignmentSourceDocumentId(syllabus.getId());
        assignments.deleteBySourceDocumentId(syllabus.getId());
        tasks.deleteByDocumentId(syllabus.getId());
        documents.deleteById(syllabus.getId());
        flushAndClear();

        assertThat(documents.findById(syllabus.getId())).isEmpty();
        Assignment kept = assignments.findById(worked.getId()).orElseThrow();
        assertThat(kept.getStatus()).isEqualTo(AssignmentStatus.CLOSED);
        assertThat(kept.getSourceDocument()).isNull();
        Task keptTask = onlyTaskOf(kept);
        assertThat(keptTask.getDocument()).isNull();
        assertThat(submissions.count()).isEqualTo(1);
        assertThat(tasks.findById(legacy.getId())).as("work without an assignment survives too").isPresent();
        assertThat(assignments.findById(untouched.getId())).isEmpty();
        assertThat(tasks.findViewsByUserId(student.getId(), PageRequest.of(0, 10)).getContent())
                .as("still in the student's tasks").extracting(view -> view.id()).contains(keptTask.getId());
    }

    @Test
    void deletingByHandIsRefusedWithActivityAndAllowedWithout() {
        Assignment worked = fromSyllabus("Lab 1");
        Assignment untouched = fromSyllabus("Lab 2");
        service.handOut(course.getId());
        Task task = onlyTaskOf(worked);
        task.setStatus(TaskStatus.IN_PROGRESS); // A changed status alone counts as activity.
        flushAndClear();

        assertThatThrownBy(() -> service.delete(worked.getId(), "t@uni.kz")).isInstanceOfSatisfying(
                ApiException.class, ex -> assertThat(ex.getCode()).isEqualTo(ErrorCodes.ASSIGNMENT_HAS_ACTIVITY));
        service.delete(untouched.getId(), "t@uni.kz");
        flushAndClear();

        assertThat(assignments.findById(worked.getId())).isPresent();
        assertThat(tasks.findById(task.getId())).isPresent();
        assertThat(assignments.findById(untouched.getId())).isEmpty();

        service.close(worked.getId(), "t@uni.kz");
        flushAndClear();
        assertThat(assignments.findStatusById(worked.getId())).contains(AssignmentStatus.CLOSED);
        assertThat(tasks.findViewById(task.getId()).orElseThrow().assignmentStatus()).isEqualTo(AssignmentStatus.CLOSED);
    }

    // ---------------- US-08: AI drafts ----------------

    @Test
    void aiDraftsAreInvisibleToStudentsUntilPublished() {
        service.replaceFromDocument(syllabus.getId(), course.getId(), List.of(
                new TaskExtractDto("Lab 1", "Sorting", LocalDateTime.now().plusDays(7))));
        flushAndClear();
        Assignment draft = assignments.findBySourceDocumentId(syllabus.getId()).get(0);
        assertThat(draft.getStatus()).isEqualTo(AssignmentStatus.DRAFT);

        // Nowhere for the student: no task, not in the course or document list, not in their tasks or deadlines.
        assertThat(tasks.findAll()).isEmpty();
        assertThat(assignments.findViewsByCourseId(course.getId(), PageRequest.of(0, 10)).getContent()).isEmpty();
        assertThat(assignments.findViewsBySourceDocumentId(syllabus.getId(), PageRequest.of(0, 10)).getContent()).isEmpty();
        assertThat(tasks.findViewsByUserId(student.getId(), PageRequest.of(0, 10)).getContent()).isEmpty();
        assertThat(tasks.findUpcomingViews(student.getId(), LocalDateTime.now(), PageRequest.of(0, 10)).getContent())
                .isEmpty();
        // The teacher's review list has it.
        assertThat(assignments.findDraftViewsByCourseId(course.getId(), PageRequest.of(0, 10)).getContent())
                .extracting(view -> view.id()).containsExactly(draft.getId());

        service.publish(draft.getId(), "t@uni.kz");
        flushAndClear();

        assertThat(tasks.findAll()).extracting(task -> task.getUser().getId()).containsExactly(student.getId());
        assertThat(assignments.findViewsByCourseId(course.getId(), PageRequest.of(0, 10)).getContent())
                .extracting(view -> view.status()).containsExactly(AssignmentStatus.ACTIVE);
        assertThat(tasks.findUpcomingViews(student.getId(), LocalDateTime.now(), PageRequest.of(0, 10)).getContent())
                .hasSize(1);
        assertThat(assignments.findDraftViewsByCourseId(course.getId(), PageRequest.of(0, 10)).getContent()).isEmpty();
    }

    @Test
    void enrollingLaterNeverHandsOutDrafts() {
        Assignment draft = fromSyllabus("Draft lab");
        draft.setStatus(AssignmentStatus.DRAFT);
        flushAndClear();
        enrollments.unenroll(course.getId(), student.getId());
        enrollments.enroll(course.getId(), student.getId());

        assertThat(service.handOut(course.getId())).isZero();
        assertThat(tasks.findAll()).isEmpty();
    }

    @Test
    void reAnalysisReplacesDraftsAndKeepsWorkedOnPublishedAssignments() {
        Assignment published = fromSyllabus("Lab 1");
        service.handOut(course.getId());
        workOn(onlyTaskOf(published));
        service.replaceFromDocument(syllabus.getId(), course.getId(), List.of(new TaskExtractDto("Lab 2", null, null)));
        flushAndClear();
        Long firstDraft = assignments.findDraftViewsByCourseId(course.getId(), PageRequest.of(0, 10)).getContent()
                .get(0).id();

        // The AI words both differently now: the draft is simply replaced; the worked-on lab is closed, not deleted.
        service.replaceFromDocument(syllabus.getId(), course.getId(), List.of(new TaskExtractDto("Lab two", null, null)));
        flushAndClear();

        assertThat(assignments.findById(firstDraft)).isEmpty();
        assertThat(assignments.findDraftViewsByCourseId(course.getId(), PageRequest.of(0, 10)).getContent())
                .extracting(view -> view.title()).containsExactly("Lab two");
        assertThat(assignments.findStatusById(published.getId())).contains(AssignmentStatus.CLOSED);
        assertThat(submissions.count()).isEqualTo(1);
    }

    /** A database created before DRAFT existed has a check constraint without it; the startup migration fixes that. */
    @Test
    void migrationLetsAnOldSchemaAcceptDrafts() {
        jdbc.execute("ALTER TABLE assignments DROP CONSTRAINT IF EXISTS assignments_status_check");
        jdbc.execute("ALTER TABLE assignments ADD CONSTRAINT assignments_status_check CHECK (status IN ('ACTIVE', 'CLOSED'))");
        Assignment kept = fromSyllabus("Published before the upgrade");

        new AssignmentStatusMigration(jdbc).run(null);
        Assignment draft = fromSyllabus("New draft");
        draft.setStatus(AssignmentStatus.DRAFT);
        flushAndClear();

        assertThat(assignments.findStatusById(draft.getId())).contains(AssignmentStatus.DRAFT);
        assertThat(assignments.findStatusById(kept.getId())).as("existing rows keep their status")
                .contains(AssignmentStatus.ACTIVE);
    }

    // ---------------- US-08 step 2: editing, close/reopen ----------------

    @Test
    void editReachesTheTasksAndKeepsStatusesAndSubmissions() {
        Assignment lab = fromSyllabus("Lab 1");
        service.handOut(course.getId());
        Task task = onlyTaskOf(lab);
        workOn(task);
        flushAndClear();

        LocalDateTime deadline = LocalDateTime.of(2026, 12, 1, 23, 59);
        service.update(lab.getId(), new UpdateAssignmentRequest("Lab 1: sorting", "Quicksort", deadline), "t@uni.kz");
        flushAndClear();

        Task synced = tasks.findById(task.getId()).orElseThrow();
        assertThat(synced.getTitle()).isEqualTo("Lab 1: sorting");
        assertThat(synced.getDescription()).isEqualTo("Quicksort");
        assertThat(synced.getDeadline()).isEqualTo(deadline);
        assertThat(synced.getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
        assertThat(submissions.count()).isEqualTo(1);
    }

    @Test
    void reopenHandsOutTheTaskToStudentsWhoEnrolledWhileItWasClosed() {
        Assignment lab = fromSyllabus("Lab 1");
        service.handOut(course.getId());
        service.close(lab.getId(), "t@uni.kz");
        User late = persist(User.builder().email("late@uni.kz").fullName("Late").role(Role.ROLE_STUDENT)
                .createdAt(LocalDateTime.now()).build());
        enrollments.enroll(course.getId(), late.getId());
        service.handOut(course.getId());
        flushAndClear();
        assertThat(tasks.findAll()).as("closed: not handed out").hasSize(1);

        service.reopen(lab.getId(), "t@uni.kz");
        flushAndClear();

        assertThat(tasks.findAll()).extracting(t -> t.getUser().getId())
                .containsExactlyInAnyOrder(student.getId(), late.getId());
    }

    // ---------------- US-08 step 3: duplicate drafts ----------------

    @Test
    void draftIsFlaggedOnlyAgainstItsOwnCourseAndTheFlagGoesWithTheOriginal() {
        Course other = persist(Course.builder().title("Databases").code("DB").teacher(teacher).build());
        persist(Assignment.builder().title("Лабораторная работа 2").status(AssignmentStatus.ACTIVE).course(other).build());
        Assignment original = persist(Assignment.builder().title("Лабораторная работа №1").status(AssignmentStatus.ACTIVE)
                .course(course).build());
        flushAndClear();

        service.replaceFromDocument(syllabus.getId(), course.getId(), List.of(
                new TaskExtractDto("Лаб. 1", null, null), new TaskExtractDto("Лаб. 2", null, null)));
        flushAndClear();

        List<AssignmentResponse> drafts = assignments.findDraftViewsByCourseId(course.getId(), PageRequest.of(0, 10))
                .getContent();
        assertThat(drafts).extracting(AssignmentResponse::title, AssignmentResponse::duplicateOfId)
                .containsExactlyInAnyOrder(tuple("Лаб. 1", original.getId()), tuple("Лаб. 2", null));

        // Through the service, as the app deletes: the original was handed out with the drafts' analysis, and
        // tasks.assignment_id (no cascade, student work is never dropped silently) needs its tasks gone first.
        service.delete(original.getId(), "t@uni.kz");
        flushAndClear();
        assertThat(assignments.findDraftViewsByCourseId(course.getId(), PageRequest.of(0, 10)).getContent())
                .extracting(AssignmentResponse::duplicateOfId).containsOnlyNulls();
    }

    // ---------------- US-09: masked copies ----------------

    @Test
    void privacyBatchFollowsTheFingerprintAndAiSourcesReadTheMaskedCopy() {
        Document doc = em.find(Document.class, syllabus.getId());
        doc.setExtractedText("ИИН 990101300013, t@uni.kz");
        flushAndClear();

        assertThat(documents.findPrivacyBatch("fp-1", 0L, PageRequest.of(0, 10)))
                .extracting(source -> source.id(), source -> source.teacherEmail())
                .containsExactly(tuple(syllabus.getId(), "t@uni.kz"));
        documents.updatePrivacy(syllabus.getId(), "ИИН [ИИН скрыт], t@uni.kz", "ИИН [ИИН скрыт]", 1, "IIN", "fp-1");
        flushAndClear();

        assertThat(documents.findPrivacyBatch("fp-1", 0L, PageRequest.of(0, 10))).as("done for these settings").isEmpty();
        assertThat(documents.findPrivacyBatch("fp-2", 0L, PageRequest.of(0, 10))).as("other settings").hasSize(1);
        assertThat(documents.findClassificationSource(syllabus.getId()).orElseThrow().maskedText())
                .isEqualTo("ИИН [ИИН скрыт], t@uni.kz");
        assertThat(documents.findAnalysisSource(syllabus.getId()).orElseThrow().maskedText())
                .doesNotContain("990101300013");
    }

    @Test
    void searchByAnIinFindsNothingForAnyoneButOrdinaryWordsStillMatch() {
        new DocumentSearchSchema(jdbc).run(null); // The real text_tsv definition, over masked_text.
        Document doc = em.find(Document.class, syllabus.getId());
        doc.setExtractedText("Староста Иванов, ИИН 990101300013, лабораторная работа");
        doc.setMaskedText("Староста Иванов, ИИН [ИИН скрыт], лабораторная работа");
        flushAndClear();

        // all=true: ADMIN, every course, TEACHER_ONLY included - the widest search there is.
        assertThat(documents.searchVisibleIds("990101300013", true, List.of(course.getId()), true,
                PageRequest.of(0, 10)).getContent()).isEmpty();
        assertThat(documents.searchVisibleIds("лабораторная", true, List.of(course.getId()), true,
                PageRequest.of(0, 10)).getContent()).containsExactly(syllabus.getId());
    }

    /** Fix 2 (B): the student queries leave out what the AI or the OCR failed on, until a type is set by hand. */
    @Test
    void studentQueriesLeaveOutFailedDocumentsTeacherQueriesKeepThem() {
        new DocumentSearchSchema(jdbc).run(null);
        Document failedAi = scan("failed-ai.pdf", AiStatus.FAILED, null);
        Document failedOcr = scan("failed-ocr.pdf", AiStatus.DONE, OcrStatus.FAILED);
        Document typedByHand = scan("typed-by-hand.pdf", AiStatus.MANUAL, OcrStatus.FAILED);
        Document pending = scan("pending.pdf", AiStatus.PENDING, OcrStatus.PENDING);
        for (Document doc : List.of(syllabus, failedAi, failedOcr, typedByHand, pending)) {
            Document managed = em.find(Document.class, doc.getId());
            managed.setMaskedText("лабораторная работа");
            DocumentChunk chunk = new DocumentChunk();
            chunk.setDocument(managed);
            chunk.setEndOffset(19);
            chunk.setContent("лабораторная работа");
            persist(chunk);
        }
        flushAndClear();
        List<Long> courseIds = List.of(course.getId());

        assertThat(listed(false, false)).containsExactlyInAnyOrder(syllabus.getId(), typedByHand.getId());
        assertThat(listed(true, false)).containsExactlyInAnyOrder(syllabus.getId(), failedAi.getId(),
                failedOcr.getId(), typedByHand.getId(), pending.getId());
        assertThat(listed(true, true)).as("Требуют проверки").containsExactlyInAnyOrder(failedAi.getId(), failedOcr.getId());
        assertThat(documents.findAllSummaries(course.getId(), true, PageRequest.of(0, 10)).getContent())
                .extracting(DocumentSummary::id).containsExactlyInAnyOrder(failedAi.getId(), failedOcr.getId());

        assertThat(documents.searchVisibleIds("лабораторная", false, courseIds, false, PageRequest.of(0, 10))
                .getContent()).containsExactlyInAnyOrder(syllabus.getId(), typedByHand.getId());
        assertThat(documents.searchVisibleIds("лабораторная", false, courseIds, true, PageRequest.of(0, 10))
                .getTotalElements()).isEqualTo(5);

        assertThat(chunks.searchInCourses(courseIds, false, "лабораторная", 10))
                .extracting(DocumentChunkRepository.ChunkHit::getDocumentId)
                .containsExactlyInAnyOrder(syllabus.getId(), typedByHand.getId());
        assertThat(chunks.searchInCourses(courseIds, true, "лабораторная", 10)).hasSize(5);
    }

    /**
     * Fix 2 (B), native SQL: the search count query agrees with its page, a failed scan reappears in student search
     * and course Q&A retrieval once its type is set by hand (MANUAL), and TEACHER_ONLY stays out whatever the status.
     */
    @Test
    void typingAFailedScanByHandBringsItBackIntoStudentSearchAndChunks() {
        new DocumentSearchSchema(jdbc).run(null);
        Document examKey = scan("exam-key.pdf", AiStatus.DONE, OcrStatus.FAILED);
        Document answers = scan("answers.pdf", AiStatus.MANUAL, null);
        for (Document doc : List.of(examKey, answers)) {
            Document managed = em.find(Document.class, doc.getId());
            managed.setMaskedText("экзаменационный билет");
            if (doc == answers) {
                managed.setVisibility(DocumentVisibility.TEACHER_ONLY);
            }
            DocumentChunk chunk = new DocumentChunk();
            chunk.setDocument(managed);
            chunk.setEndOffset(21);
            chunk.setContent("экзаменационный билет");
            persist(chunk);
        }
        flushAndClear();
        List<Long> courseIds = List.of(course.getId());

        var student = documents.searchVisibleIds("билет", false, courseIds, false, PageRequest.of(0, 10));
        assertThat(student.getContent()).isEmpty();
        assertThat(student.getTotalElements()).as("count query").isZero();
        assertThat(chunks.searchInCourses(courseIds, false, "билет", 10)).isEmpty();
        assertThat(documents.searchVisibleIds("билет", false, courseIds, true, PageRequest.of(0, 10))
                .getTotalElements()).as("the teacher sees both").isEqualTo(2);

        em.find(Document.class, examKey.getId()).setAiStatus(AiStatus.MANUAL); // "Указать тип вручную"
        flushAndClear();

        student = documents.searchVisibleIds("билет", false, courseIds, false, PageRequest.of(0, 10));
        assertThat(student.getContent()).containsExactly(examKey.getId());
        assertThat(student.getTotalElements()).isEqualTo(1);
        assertThat(chunks.searchInCourses(courseIds, false, "билет", 10))
                .extracting(DocumentChunkRepository.ChunkHit::getDocumentId).containsExactly(examKey.getId());
    }

    private List<Long> listed(boolean includeTeacherOnly, boolean needsReview) {
        return documents.findSummariesInCourses(List.of(course.getId()), includeTeacherOnly, course.getId(),
                needsReview, PageRequest.of(0, 10)).getContent().stream().map(DocumentSummary::id).toList();
    }

    private Document scan(String name, AiStatus aiStatus, OcrStatus ocrStatus) {
        return persist(Document.builder().originalFileName(name)
                .storedFileName(UUID.randomUUID().toString()).contentType("application/pdf").fileSize(1L)
                .category(DocumentCategory.OTHER).type(DocumentType.OTHER).aiStatus(aiStatus).ocrStatus(ocrStatus)
                .visibility(DocumentVisibility.COURSE_STUDENTS).course(course).owner(teacher)
                .uploadedAt(LocalDateTime.now()).build());
    }

    private Assignment fromSyllabus(String title) {
        return persist(Assignment.builder().title(title).status(AssignmentStatus.ACTIVE).course(course)
                .sourceDocument(syllabus).build());
    }

    private Task onlyTaskOf(Assignment assignment) {
        List<Task> found = tasks.findAll().stream()
                .filter(task -> task.getAssignment() != null && task.getAssignment().getId().equals(assignment.getId()))
                .toList();
        assertThat(found).hasSize(1);
        return found.get(0);
    }

    /** The student hands in a file and starts working: the task now carries student work. */
    private void workOn(Task task) {
        Task managed = em.find(Task.class, task.getId());
        managed.setStatus(TaskStatus.IN_PROGRESS);
        persist(TaskSubmission.builder().task(managed).student(em.find(User.class, student.getId()))
                .originalFileName("solution.pdf").storedFileName(UUID.randomUUID().toString().replace("-", ""))
                .contentType("application/pdf").fileSize(1L).build());
    }

    private <T> T persist(T entity) {
        em.persist(entity);
        em.flush();
        return entity;
    }

    private void flushAndClear() {
        em.flush();
        em.clear();
    }
}

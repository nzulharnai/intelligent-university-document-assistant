package com.university.docassistant.controller;

import com.university.docassistant.dto.AuthDtos.MessageResponse;
import com.university.docassistant.dto.BatchImportResponse;
import com.university.docassistant.dto.CourseDtos.AssignTeacherRequest;
import com.university.docassistant.dto.CourseDtos.CourseDetailsResponse;
import com.university.docassistant.dto.CourseDtos.CourseResponse;
import com.university.docassistant.dto.CourseDtos.CreateCourseRequest;
import com.university.docassistant.dto.CourseDtos.EnrollRequest;
import com.university.docassistant.dto.CourseDtos.InviteCodeResponse;
import com.university.docassistant.dto.CourseDtos.JoinCourseRequest;
import com.university.docassistant.dto.CourseDtos.JoinCourseResponse;
import com.university.docassistant.dto.CourseDtos.StudentResponse;
import com.university.docassistant.dto.DocumentAnswerResponse;
import com.university.docassistant.dto.DocumentQuestionRequest;
import com.university.docassistant.dto.PageResponse;
import com.university.docassistant.service.CourseService;
import com.university.docassistant.service.AiConversationService;
import com.university.docassistant.service.DocumentQnaService;
import com.university.docassistant.service.DocumentService;
import com.university.docassistant.util.SortValidator;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

@RestController
@RequestMapping("/api/v1/courses")
@RequiredArgsConstructor
public class CourseController {

    private static final Set<String> SORTABLE_FIELDS = Set.of("title", "code", "id");
    private static final Set<String> STUDENT_SORTABLE_FIELDS = Set.of("fullName", "email", "id");

    private final CourseService courseService;
    private final DocumentQnaService documentQnaService;
    private final AiConversationService conversationService;
    private final DocumentService documentService;

    @PostMapping
    @PreAuthorize("hasAnyRole('TEACHER', 'ADMIN')")
    public ResponseEntity<CourseResponse> create(@Valid @RequestBody CreateCourseRequest request,
                                                 @AuthenticationPrincipal UserDetails principal) {
        return ResponseEntity.status(HttpStatus.CREATED).body(courseService.create(request, principal.getUsername()));
    }

    /** {@code q}: optional title or code search (2+ characters) within the user's courses. */
    @GetMapping
    public PageResponse<CourseResponse> list(@RequestParam(value = "q", required = false) String query,
                                             @PageableDefault(size = 20, sort = "title") Pageable pageable,
                                             @AuthenticationPrincipal UserDetails principal) {
        return courseService.listMine(principal.getUsername(), query,
                SortValidator.requireSortable(pageable, SORTABLE_FIELDS));
    }

    @GetMapping("/{id}")
    public CourseDetailsResponse details(@PathVariable Long id, @AuthenticationPrincipal UserDetails principal) {
        return courseService.details(id, principal.getUsername());
    }

    /** A student joins a course with the code from the teacher's invitation link; returns the course id. */
    @PostMapping("/join")
    public JoinCourseResponse join(@Valid @RequestBody JoinCourseRequest request,
                                   @AuthenticationPrincipal UserDetails principal) {
        return new JoinCourseResponse(courseService.join(request.code(), principal.getUsername()));
    }

    /** The course's invitation code; for its teacher and ADMIN. */
    @GetMapping("/{id}/invite")
    public InviteCodeResponse inviteCode(@PathVariable Long id, @AuthenticationPrincipal UserDetails principal) {
        return new InviteCodeResponse(courseService.inviteCode(id, principal.getUsername()));
    }

    /** A new invitation code; the old link stops working immediately. */
    @PostMapping("/{id}/invite/regenerate")
    public InviteCodeResponse regenerateInviteCode(@PathVariable Long id,
                                                   @AuthenticationPrincipal UserDetails principal) {
        return new InviteCodeResponse(courseService.regenerateInviteCode(id, principal.getUsername()));
    }

    /** The course's teacher or ADMIN enrolls the student {@code studentId}; students join by invitation code. */
    @PostMapping("/{id}/enroll")
    public MessageResponse enroll(@PathVariable Long id,
                                  @RequestBody(required = false) EnrollRequest request,
                                  @AuthenticationPrincipal UserDetails principal) {
        courseService.enroll(id, request != null ? request.studentId() : null, principal.getUsername());
        return new MessageResponse("Enrolled");
    }

    /** The course's ZIP imports, newest first (the order is fixed, so {@code sort} is ignored); teacher and ADMIN. */
    @GetMapping("/{id}/imports")
    public PageResponse<BatchImportResponse> imports(@PathVariable Long id,
                                                     @PageableDefault(size = 20) Pageable pageable,
                                                     @AuthenticationPrincipal UserDetails principal) {
        return documentService.imports(id, principal.getUsername(), pageable);
    }

    /** The course's students; for its teacher and ADMIN. */
    @GetMapping("/{id}/students")
    @PreAuthorize("hasAnyRole('TEACHER', 'ADMIN')")
    public PageResponse<StudentResponse> students(@PathVariable Long id,
                                                  @PageableDefault(size = 50, sort = "fullName") Pageable pageable,
                                                  @AuthenticationPrincipal UserDetails principal) {
        return courseService.students(id, principal.getUsername(),
                SortValidator.requireSortable(pageable, STUDENT_SORTABLE_FIELDS));
    }

    /** Removes a student from the course; for the student themselves, the course teacher or ADMIN. */
    @DeleteMapping("/{id}/students/{studentId}")
    public ResponseEntity<Void> unenroll(@PathVariable Long id, @PathVariable Long studentId,
                                         @AuthenticationPrincipal UserDetails principal) {
        courseService.unenroll(id, studentId, principal.getUsername());
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{id}/teacher")
    @PreAuthorize("hasRole('ADMIN')")
    public CourseResponse assignTeacher(@PathVariable Long id, @Valid @RequestBody AssignTeacherRequest request,
                                        @AuthenticationPrincipal UserDetails principal) {
        return courseService.assignTeacher(id, request.teacherId(), principal.getUsername());
    }

    /** Answers from the course's documents the user may see; each source names its document. */
    @PostMapping("/{id}/ask")
    public DocumentAnswerResponse ask(@PathVariable Long id,
                                      @Valid @RequestBody DocumentQuestionRequest request,
                                      @AuthenticationPrincipal UserDetails principal) {
        return conversationService.limited(principal.getUsername(),
                () -> documentQnaService.askCourse(id, request.question(), principal.getUsername()));
    }
}

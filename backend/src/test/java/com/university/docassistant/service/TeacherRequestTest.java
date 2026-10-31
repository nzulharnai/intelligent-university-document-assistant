package com.university.docassistant.service;

import com.university.docassistant.dto.AdminDtos.AdminUserResponse;
import com.university.docassistant.dto.AuthDtos.MessageResponse;
import com.university.docassistant.dto.AuthDtos.RegisterRequest;
import com.university.docassistant.entity.AuthProvider;
import com.university.docassistant.entity.NotificationType;
import com.university.docassistant.entity.Role;
import com.university.docassistant.entity.User;
import com.university.docassistant.exception.ApiException;
import com.university.docassistant.repository.AssignmentRepository;
import com.university.docassistant.repository.AuditLogRepository;
import com.university.docassistant.repository.CourseRepository;
import com.university.docassistant.repository.DocumentRepository;
import com.university.docassistant.repository.EnrollmentRepository;
import com.university.docassistant.repository.TaskRepository;
import com.university.docassistant.repository.TaskSubmissionRepository;
import com.university.docassistant.repository.UserRepository;
import com.university.docassistant.security.JwtTokenProvider;
import com.university.docassistant.security.LoginAttemptLimiter;
import com.university.docassistant.security.OAuth2SuccessHandler;
import com.university.docassistant.service.storage.StorageService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Nobody becomes a teacher by themselves: registration and Google sign-up create students, ADMIN decides. */
class TeacherRequestTest {

    private static final String ADMIN_EMAIL = "dean@uni.kz";

    private final UserRepository users = mock(UserRepository.class);
    private final TaskRepository tasks = mock(TaskRepository.class);
    private final EnrollmentRepository enrollments = mock(EnrollmentRepository.class);
    private final AuditService audit = mock(AuditService.class);
    private final NotificationService notifications = mock(NotificationService.class);

    @Test
    void registeringAsTeacherCreatesAStudentWithAPendingRequest() {
        AuthService auth = authService();
        when(users.existsByEmail("t@uni.kz")).thenReturn(false);

        MessageResponse response = auth.register(
                new RegisterRequest("T@uni.kz", "password123", "Иван Петров", Role.ROLE_TEACHER));

        User saved = savedUser();
        assertThat(saved.getRole()).isEqualTo(Role.ROLE_STUDENT);
        assertThat(saved.isTeacherRequested()).isTrue();
        assertThat(response.message()).contains("деканат").contains("студент");
    }

    @Test
    void registeringAsStudentHasNoRequest() {
        AuthService auth = authService();

        MessageResponse response = auth.register(new RegisterRequest("s@uni.kz", "password123", "Студент", null));

        User saved = savedUser();
        assertThat(saved.getRole()).isEqualTo(Role.ROLE_STUDENT);
        assertThat(saved.isTeacherRequested()).isFalse();
        assertThat(response.message()).doesNotContain("деканат");
    }

    @Test
    void registeringAsAdminIsStillForbidden() {
        AuthService auth = authService();

        assertThatThrownBy(() -> auth.register(new RegisterRequest("a@uni.kz", "password123", "A", Role.ROLE_ADMIN)))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        verify(users, never()).save(any());
    }

    @Test
    void approvingMakesTheStudentATeacherAndClearsTheRequest() {
        User applicant = applicant();
        when(users.findById(7L)).thenReturn(Optional.of(applicant));

        AdminUserResponse response = adminService().approveTeacherRequest(7L, ADMIN_EMAIL);

        assertThat(applicant.getRole()).isEqualTo(Role.ROLE_TEACHER);
        assertThat(applicant.isTeacherRequested()).isFalse();
        assertThat(response.role()).isEqualTo(Role.ROLE_TEACHER);
        // Same path as any role change: the old student relations go, and both decisions are audited.
        verify(enrollments).deleteByStudentId(7L);
        verify(audit).record(eq(ADMIN_EMAIL), eq(AuditService.ROLE_CHANGED), anyString());
        verify(audit).record(ADMIN_EMAIL, AuditService.TEACHER_REQUEST_APPROVED, "applicant@uni.kz");
        // US-13: the applicant, and only they, hears the decision.
        verify(notifications).notify(eq(List.of(7L)), eq(NotificationType.TEACHER_REQUEST_APPROVED), anyString(),
                anyString(), eq("/courses"));
    }

    @Test
    void rejectingClearsTheRequestAndKeepsTheStudent() {
        User applicant = applicant();
        when(users.findById(7L)).thenReturn(Optional.of(applicant));

        adminService().rejectTeacherRequest(7L, ADMIN_EMAIL);

        assertThat(applicant.getRole()).isEqualTo(Role.ROLE_STUDENT);
        assertThat(applicant.isTeacherRequested()).isFalse();
        verify(users).save(applicant);
        verify(enrollments, never()).deleteByStudentId(any());
        verify(audit).record(ADMIN_EMAIL, AuditService.TEACHER_REQUEST_REJECTED, "applicant@uni.kz");
        verify(notifications).notify(eq(List.of(7L)), eq(NotificationType.TEACHER_REQUEST_REJECTED), anyString(),
                anyString(), anyString());
    }

    @Test
    void thereIsNothingToDecideWithoutAPendingRequest() {
        User student = applicant();
        student.setTeacherRequested(false);
        when(users.findById(7L)).thenReturn(Optional.of(student));

        assertThatThrownBy(() -> adminService().approveTeacherRequest(7L, ADMIN_EMAIL))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThat(student.getRole()).isEqualTo(Role.ROLE_STUDENT);
    }

    @Test
    void googleSignUpCreatesAStudent() throws Exception {
        JwtTokenProvider jwt = mock(JwtTokenProvider.class);
        when(jwt.generateToken(anyString(), any())).thenReturn("token");
        when(users.findByEmail("new@gmail.com")).thenReturn(Optional.empty());
        when(users.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
        OAuth2SuccessHandler handler = new OAuth2SuccessHandler(users, jwt, "http://localhost:5173");
        DefaultOAuth2User google = new DefaultOAuth2User(List.of(),
                Map.of("sub", "1", "email", "New@gmail.com", "name", "Новый", "email_verified", true), "sub");

        handler.onAuthenticationSuccess(new MockHttpServletRequest(), new MockHttpServletResponse(),
                new TestingAuthenticationToken(google, null));

        User saved = savedUser();
        assertThat(saved.getRole()).isEqualTo(Role.ROLE_STUDENT);
        assertThat(saved.isTeacherRequested()).isFalse();
        assertThat(saved.getProvider()).isEqualTo(AuthProvider.GOOGLE);
        verify(jwt).generateToken("new@gmail.com", Role.ROLE_STUDENT);
    }

    private User applicant() {
        return User.builder().id(7L).email("applicant@uni.kz").fullName("Заявитель").role(Role.ROLE_STUDENT)
                .teacherRequested(true).build();
    }

    private User savedUser() {
        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(users).save(saved.capture());
        return saved.getValue();
    }

    private AuthService authService() {
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        when(encoder.encode(anyString())).thenReturn("hash");
        return new AuthService(users, encoder, mock(AuthenticationManager.class), mock(JwtTokenProvider.class),
                mock(EmailService.class), 10, 60, 5,
                new LoginAttemptLimiter(), audit);
    }

    private AdminService adminService() {
        return new AdminService(users, mock(DocumentRepository.class), mock(CourseRepository.class), tasks,
                mock(TaskSubmissionRepository.class), mock(AssignmentRepository.class), enrollments,
                mock(AuditLogRepository.class), audit, mock(StorageService.class), mock(AssignmentService.class),
                notifications);
    }
}

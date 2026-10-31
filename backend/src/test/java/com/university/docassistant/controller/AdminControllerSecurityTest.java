package com.university.docassistant.controller;

import com.university.docassistant.service.AdminService;
import com.university.docassistant.service.DeadlineReminderService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * The real @PreAuthorize("hasRole('ADMIN')") on AdminController, enforced by Spring method security; the denial
 * becomes 403 "Access denied" in GlobalExceptionHandler.
 */
@SpringJUnitConfig(AdminControllerSecurityTest.Config.class)
class AdminControllerSecurityTest {

    @Configuration
    @EnableMethodSecurity
    static class Config {
        @Bean
        AdminService adminService() {
            return mock(AdminService.class);
        }

        @Bean
        DeadlineReminderService deadlineReminderService() {
            return mock(DeadlineReminderService.class);
        }

        @Bean
        AdminController adminController(AdminService adminService, DeadlineReminderService reminders) {
            return new AdminController(adminService, reminders);
        }
    }

    @Autowired
    private AdminController controller;

    @Autowired
    private AdminService adminService;

    @Autowired
    private DeadlineReminderService reminders;

    private final UserDetails principal = User.withUsername("someone@uni.kz").password("x").roles("STUDENT").build();

    @Test
    @WithMockUser(roles = "STUDENT")
    void studentCannotApproveRejectOrListTeacherRequests() {
        clearInvocations(adminService);
        assertThatThrownBy(() -> controller.approveTeacherRequest(7L, principal)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.rejectTeacherRequest(7L, principal)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.teacherRequests(0, 20)).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(adminService);
    }

    @Test
    @WithMockUser(roles = "TEACHER")
    void teacherCannotApproveEither() {
        clearInvocations(adminService);
        assertThatThrownBy(() -> controller.approveTeacherRequest(7L, principal)).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(adminService);
    }

    @Test
    @WithMockUser(username = "dean@uni.kz", roles = "ADMIN")
    void adminCanApprove() {
        clearInvocations(adminService);
        UserDetails admin = User.withUsername("dean@uni.kz").password("x").roles("ADMIN").build();

        controller.approveTeacherRequest(7L, admin);

        verify(adminService).approveTeacherRequest(7L, "dean@uni.kz");
    }

    @Test
    @WithMockUser(roles = "TEACHER")
    void onlyAdminRunsTheDeadlineRemindersByHand() {
        clearInvocations(reminders);
        assertThatThrownBy(() -> controller.runReminders(principal)).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(reminders);
    }

    @Test
    @WithMockUser(username = "dean@uni.kz", roles = "ADMIN")
    void adminRunsTheDeadlineReminders() {
        clearInvocations(reminders);
        UserDetails admin = User.withUsername("dean@uni.kz").password("x").roles("ADMIN").build();

        controller.runReminders(admin);

        verify(reminders).runNow("dean@uni.kz");
    }
}

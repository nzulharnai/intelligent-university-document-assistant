package com.university.docassistant.dto;

import com.university.docassistant.entity.AuthProvider;
import com.university.docassistant.entity.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request/response DTOs for authentication endpoints.
 */
public final class AuthDtos {

    private AuthDtos() {
    }

    public static final String PASSWORD_TOO_SHORT = "Пароль слишком короткий. Минимальная длина — 8 символов.";

    // Messages are shown to the user as-is (fieldErrors of a 400), so they are written for the login/register form.
    public record RegisterRequest(
            @NotBlank(message = "Введите email") @Email(message = "Введите корректный email") String email,
            @NotBlank(message = "Введите пароль")
            @Size(min = 8, message = PASSWORD_TOO_SHORT)
            @Size(max = 100, message = "Пароль слишком длинный. Максимальная длина — 100 символов.") String password,
            @NotBlank(message = "Введите ФИО") @Size(max = 150, message = "ФИО не длиннее 150 символов") String fullName,
            Role role
    ) {
    }

    public record LoginRequest(
            @NotBlank(message = "Введите email") @Email(message = "Введите корректный email") String email,
            @NotBlank(message = "Введите пароль") String password
    ) {
    }

    public record ForgotPasswordRequest(
            @NotBlank @Email String email
    ) {
    }

    public record VerifyOtpRequest(
            @NotBlank @Email String email,
            @NotBlank @Pattern(regexp = "\\d{6}", message = "OTP must be 6 digits") String otp
    ) {
    }

    public record ResetPasswordRequest(
            @NotBlank @Email String email,
            @NotBlank @Pattern(regexp = "\\d{6}", message = "OTP must be 6 digits") String otp,
            @NotBlank @Size(min = 8, max = 100) String newPassword
    ) {
    }

    public record VerifyEmailRequest(
            @NotBlank @Email String email,
            @NotBlank @Pattern(regexp = "\\d{6}", message = "OTP must be 6 digits") String otp
    ) {
    }

    public record ResendVerificationRequest(
            @NotBlank @Email String email
    ) {
    }

    /** {@code teacherRequested}: a teacher role request is waiting for ADMIN (the role is still ROLE_STUDENT). */
    public record AuthResponse(
            String token,
            String email,
            String fullName,
            Role role,
            boolean teacherRequested
    ) {
    }

    public record UserResponse(
            Long id,
            String email,
            String fullName,
            Role role,
            AuthProvider provider,
            boolean emailVerified,
            boolean teacherRequested,
            /** Digits only, or null. */
            String phone,
            /** Notifications are e-mailed too (US-13). */
            boolean emailNotifications
    ) {
    }

    /** PATCH /users/me: the user's own name and phone (empty or null phone removes it). */
    public record UpdateProfileRequest(
            @NotBlank(message = "Введите ФИО") @Size(max = 150, message = "ФИО не длиннее 150 символов") String fullName,
            @Size(max = 30, message = "Телефон не длиннее 30 символов") String phone
    ) {
    }

    public record MessageResponse(
            String message
    ) {
    }

    /** Which sign-in methods the login page offers besides the password; google=false hides the Google button. */
    public record ProvidersResponse(
            boolean google
    ) {
    }
}

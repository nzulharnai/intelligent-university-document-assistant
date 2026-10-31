package com.university.docassistant.controller;

import com.university.docassistant.config.GoogleLoginSettings;
import com.university.docassistant.dto.AuthDtos.AuthResponse;
import com.university.docassistant.dto.AuthDtos.ForgotPasswordRequest;
import com.university.docassistant.dto.AuthDtos.LoginRequest;
import com.university.docassistant.dto.AuthDtos.MessageResponse;
import com.university.docassistant.dto.AuthDtos.ProvidersResponse;
import com.university.docassistant.dto.AuthDtos.RegisterRequest;
import com.university.docassistant.dto.AuthDtos.ResendVerificationRequest;
import com.university.docassistant.dto.AuthDtos.ResetPasswordRequest;
import com.university.docassistant.dto.AuthDtos.VerifyEmailRequest;
import com.university.docassistant.dto.AuthDtos.VerifyOtpRequest;
import com.university.docassistant.security.ClientIpResolver;
import com.university.docassistant.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final ClientIpResolver clientIpResolver;
    private final GoogleLoginSettings googleLogin;

    @GetMapping("/providers")
    public ResponseEntity<ProvidersResponse> providers() {
        return ResponseEntity.ok(new ProvidersResponse(googleLogin.isEnabled()));
    }

    @PostMapping("/register")
    public ResponseEntity<MessageResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.register(request));
    }

    @PostMapping("/verify-email")
    public ResponseEntity<AuthResponse> verifyEmail(@Valid @RequestBody VerifyEmailRequest request) {
        return ResponseEntity.ok(authService.verifyEmail(request));
    }

    @PostMapping("/resend-verification-otp")
    public ResponseEntity<MessageResponse> resendVerificationOtp(@Valid @RequestBody ResendVerificationRequest request) {
        return ResponseEntity.ok(authService.resendVerificationOtp(request));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        return ResponseEntity.ok(authService.login(request, clientIpResolver.resolve(http)));
    }

    @PostMapping("/forgot-password")
    public ResponseEntity<MessageResponse> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        return ResponseEntity.ok(authService.forgotPassword(request));
    }

    @PostMapping("/verify-otp")
    public ResponseEntity<MessageResponse> verifyOtp(@Valid @RequestBody VerifyOtpRequest request) {
        return ResponseEntity.ok(authService.verifyOtp(request));
    }

    @PostMapping("/reset-password")
    public ResponseEntity<MessageResponse> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        return ResponseEntity.ok(authService.resetPassword(request));
    }
}

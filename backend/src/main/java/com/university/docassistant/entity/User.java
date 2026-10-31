package com.university.docassistant.entity;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import org.hibernate.annotations.ColumnDefault;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.time.LocalDateTime;

@Entity
@Table(name = "users") // "user" is a reserved word in PostgreSQL
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String email;

    /** Null for OAuth users (e.g. GOOGLE provider). */
    @ToString.Exclude
    private String password;

    @Column(name = "full_name")
    private String fullName;

    /**
     * Optional contact phone, digits only ("77011234567"; see PhoneNumbers). A course teacher's phone stays readable
     * in the course's documents, like their e-mail (US-09).
     */
    @Column(length = 20)
    private String phone;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private AuthProvider provider = AuthProvider.LOCAL;

    @Column(name = "is_email_verified", nullable = false)
    @Builder.Default
    private boolean emailVerified = false;

    /**
     * Asked for the teacher role at registration. The account stays ROLE_STUDENT until ADMIN (Деканат) approves;
     * existing rows get false.
     */
    @Column(name = "teacher_requested", nullable = false)
    @ColumnDefault("false")
    private boolean teacherRequested;

    /** Notifications are also e-mailed (US-13); the site's bell shows them either way. Set in the profile. */
    @Column(name = "email_notifications", nullable = false)
    @ColumnDefault("true")
    @Builder.Default
    private boolean emailNotifications = true;

    @Embedded
    @AttributeOverride(name = "code", column = @Column(name = "reset_otp"))
    @AttributeOverride(name = "expiry", column = @Column(name = "reset_otp_expiry"))
    @AttributeOverride(name = "issuedAt", column = @Column(name = "reset_otp_issued_at"))
    @AttributeOverride(name = "attempts", column = @Column(name = "reset_otp_attempts",
            nullable = false, columnDefinition = "integer default 0"))
    @Builder.Default
    @ToString.Exclude
    private OtpCode resetOtp = new OtpCode();

    @Embedded
    @AttributeOverride(name = "code", column = @Column(name = "email_verification_otp"))
    @AttributeOverride(name = "expiry", column = @Column(name = "email_verification_otp_expiry"))
    @AttributeOverride(name = "issuedAt", column = @Column(name = "email_verification_otp_issued_at"))
    @AttributeOverride(name = "attempts", column = @Column(name = "email_verification_otp_attempts",
            nullable = false, columnDefinition = "integer default 0"))
    @Builder.Default
    @ToString.Exclude
    private OtpCode emailVerificationOtp = new OtpCode();

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    // Hibernate sets an embedded value to null when all its columns are null; never expose that.
    public OtpCode getResetOtp() {
        if (resetOtp == null) {
            resetOtp = new OtpCode();
        }
        return resetOtp;
    }

    public OtpCode getEmailVerificationOtp() {
        if (emailVerificationOtp == null) {
            emailVerificationOtp = new OtpCode();
        }
        return emailVerificationOtp;
    }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}

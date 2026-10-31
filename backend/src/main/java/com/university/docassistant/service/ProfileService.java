package com.university.docassistant.service;

import com.university.docassistant.dto.AuthDtos.UpdateProfileRequest;
import com.university.docassistant.dto.AuthDtos.UserResponse;
import com.university.docassistant.dto.NotificationDtos.PreferencesRequest;
import com.university.docassistant.entity.User;
import com.university.docassistant.exception.ApiException;
import com.university.docassistant.exception.ErrorCodes;
import com.university.docassistant.repository.CourseRepository;
import com.university.docassistant.security.AccessControlService;
import com.university.docassistant.util.PhoneNumbers;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The signed-in user's own profile (GET/PATCH /users/me): name and phone, for every role. A teacher's phone stays
 * readable in their courses' documents, so changing it re-masks those courses (CoursePrivacyRemask).
 */
@Service
@RequiredArgsConstructor
public class ProfileService {

    private final AccessControlService access;
    private final CourseRepository courseRepository;
    private final CoursePrivacyRemask privacyRemask;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public UserResponse me(String email) {
        return toResponse(access.currentUser(email));
    }

    /** 400 PHONE_INVALID for a phone that is not 10-15 digits; an empty phone removes it. Audited without values. */
    @Transactional
    public UserResponse update(String email, UpdateProfileRequest request) {
        User user = access.currentUser(email);
        String newPhone;
        try {
            newPhone = PhoneNumbers.normalize(request.phone());
        } catch (IllegalArgumentException ex) {
            throw ApiException.badRequest(ErrorCodes.PHONE_INVALID,
                    "Phone must have 10 to 15 digits; spaces, dashes, brackets and a leading + are allowed");
        }
        String fullName = request.fullName().strip();
        List<String> changed = new ArrayList<>();
        if (!fullName.equals(user.getFullName())) {
            user.setFullName(fullName);
            changed.add("name");
        }
        if (!Objects.equals(newPhone, user.getPhone())) {
            user.setPhone(newPhone);
            changed.add("phone");
            // The phone stays readable in the documents of the courses this user teaches: mask them again.
            privacyRemask.schedule(courseRepository.findIdsByTeacherId(user.getId()));
        }
        if (!changed.isEmpty()) {
            // Which fields, never their values: a phone is personal data.
            auditService.record(user.getEmail(), AuditService.PROFILE_UPDATED, "Changed " + String.join(", ", changed));
        }
        return toResponse(user);
    }

    /** Whether notifications are also e-mailed (US-13); audited as a profile change. */
    @Transactional
    public UserResponse updatePreferences(String email, PreferencesRequest request) {
        User user = access.currentUser(email);
        if (user.isEmailNotifications() != request.emailNotifications()) {
            user.setEmailNotifications(request.emailNotifications());
            auditService.record(user.getEmail(), AuditService.PROFILE_UPDATED,
                    "E-mail notifications " + (request.emailNotifications() ? "on" : "off"));
        }
        return toResponse(user);
    }

    static UserResponse toResponse(User user) {
        return new UserResponse(user.getId(), user.getEmail(), user.getFullName(), user.getRole(), user.getProvider(),
                user.isEmailVerified(), user.isTeacherRequested(), user.getPhone(), user.isEmailNotifications());
    }
}

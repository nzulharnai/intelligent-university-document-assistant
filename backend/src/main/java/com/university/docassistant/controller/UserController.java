package com.university.docassistant.controller;

import com.university.docassistant.dto.AuthDtos.UpdateProfileRequest;
import com.university.docassistant.dto.AuthDtos.UserResponse;
import com.university.docassistant.dto.NotificationDtos.PreferencesRequest;
import com.university.docassistant.service.ProfileService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final ProfileService profileService;

    @GetMapping("/me")
    public UserResponse me(@AuthenticationPrincipal UserDetails principal) {
        return profileService.me(principal.getUsername());
    }

    /** The user's own name and phone; any role. */
    @PatchMapping("/me")
    public UserResponse updateMe(@Valid @RequestBody UpdateProfileRequest request,
                                 @AuthenticationPrincipal UserDetails principal) {
        return profileService.update(principal.getUsername(), request);
    }

    /** {@code emailNotifications}: whether notifications are also e-mailed; any role. */
    @PatchMapping("/me/preferences")
    public UserResponse updatePreferences(@Valid @RequestBody PreferencesRequest request,
                                          @AuthenticationPrincipal UserDetails principal) {
        return profileService.updatePreferences(principal.getUsername(), request);
    }
}

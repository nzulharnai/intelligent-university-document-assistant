package com.university.docassistant.controller;

import com.university.docassistant.dto.CourseWeekDtos.AcceptAllResponse;
import com.university.docassistant.dto.CourseWeekDtos.DocumentWeekResponse;
import com.university.docassistant.dto.CourseWeekDtos.SetDocumentWeekRequest;
import com.university.docassistant.dto.CourseWeekDtos.SuggestResponse;
import com.university.docassistant.dto.CourseWeekDtos.UpdateWeekTopicRequest;
import com.university.docassistant.dto.CourseWeekDtos.UpdateWeekVisibilityRequest;
import com.university.docassistant.dto.CourseWeekDtos.UpdateWeeksCountRequest;
import com.university.docassistant.dto.CourseWeekDtos.WeekView;
import com.university.docassistant.dto.CourseWeekDtos.WeeksOverview;
import com.university.docassistant.service.CourseWeekService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * US-16: course weeks (/courses/{id}/weeks...) and a document's week (/documents/{id}/week...). Reading is for the
 * course's members; changes need MANAGE_COURSE, checked in CourseWeekService.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class CourseWeekController {

    private final CourseWeekService weekService;

    @GetMapping("/courses/{id}/weeks")
    public WeeksOverview weeks(@PathVariable Long id, @AuthenticationPrincipal UserDetails principal) {
        return weekService.weeks(id, principal.getUsername());
    }

    @PutMapping("/courses/{id}/weeks")
    public WeeksOverview updateWeeksCount(@PathVariable Long id, @Valid @RequestBody UpdateWeeksCountRequest request,
                                          @AuthenticationPrincipal UserDetails principal) {
        return weekService.updateWeeksCount(id, request.weeksCount(), principal.getUsername());
    }

    @PutMapping("/courses/{id}/weeks/{week}")
    public WeekView setTopic(@PathVariable Long id, @PathVariable int week,
                             @Valid @RequestBody UpdateWeekTopicRequest request,
                             @AuthenticationPrincipal UserDetails principal) {
        return weekService.setTopic(id, week, request.topic(), principal.getUsername());
    }

    /** US-16 AC-4: {"hidden": true} hides the week and its documents from students; false shows them again. */
    @PutMapping("/courses/{id}/weeks/{week}/visibility")
    public WeekView setHidden(@PathVariable Long id, @PathVariable int week,
                              @Valid @RequestBody UpdateWeekVisibilityRequest request,
                              @AuthenticationPrincipal UserDetails principal) {
        return weekService.setHidden(id, week, request.hidden(), principal.getUsername());
    }

    @PostMapping("/courses/{id}/weeks/accept-all")
    public AcceptAllResponse acceptAll(@PathVariable Long id, @AuthenticationPrincipal UserDetails principal) {
        return weekService.acceptAll(id, principal.getUsername());
    }

    /** 202: the hints are computed in the background; the answer says for how many documents. */
    @PostMapping("/courses/{id}/weeks/suggest")
    public ResponseEntity<SuggestResponse> suggest(@PathVariable Long id,
                                                   @AuthenticationPrincipal UserDetails principal) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(weekService.requestSuggestions(id, principal.getUsername()));
    }

    @PatchMapping("/documents/{id}/week")
    public DocumentWeekResponse setDocumentWeek(@PathVariable Long id, @RequestBody SetDocumentWeekRequest request,
                                                @AuthenticationPrincipal UserDetails principal) {
        return weekService.setDocumentWeek(id, request.week(), principal.getUsername());
    }

    @PostMapping("/documents/{id}/week/accept")
    public DocumentWeekResponse acceptSuggestion(@PathVariable Long id, @AuthenticationPrincipal UserDetails principal) {
        return weekService.acceptSuggestion(id, principal.getUsername());
    }
}

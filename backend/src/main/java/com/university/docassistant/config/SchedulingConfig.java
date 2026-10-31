package com.university.docassistant.config;

import com.university.docassistant.service.DeadlineReminderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Background jobs on Spring's scheduler. The deadline reminders run on {@code app.notifications.reminder-cron}
 * (default every 15 minutes; "-" turns them off). A failed run is logged and the next one tries again: the claim
 * column keeps a reminder from going out twice.
 */
@Slf4j
@Configuration
@EnableScheduling
@RequiredArgsConstructor
public class SchedulingConfig {

    private final DeadlineReminderService deadlineReminderService;

    @Scheduled(cron = "${app.notifications.reminder-cron:0 */15 * * * *}",
            zone = "${app.time-zone:" + ClockConfig.DEFAULT_TIME_ZONE + "}")
    public void sendDeadlineReminders() {
        try {
            deadlineReminderService.sendDue();
        } catch (RuntimeException ex) {
            log.warn("Deadline reminders failed, the next run tries again: {}", ex.toString());
        }
    }
}

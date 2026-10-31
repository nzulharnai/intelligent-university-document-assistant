package com.university.docassistant.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/**
 * The shared clock, in the app time zone ({@code app.time-zone}, default Asia/Almaty). Deadlines are local date-times
 * of that zone, so whatever compares with them (lateness, upcoming deadlines, reminders) takes "now" from this clock,
 * never from LocalDateTime.now(), which would follow the server's zone. Tests pass a fixed clock instead.
 */
@Slf4j
@Configuration
public class ClockConfig {

    public static final String DEFAULT_TIME_ZONE = "Asia/Almaty";

    @Bean
    public Clock clock(@Value("${app.time-zone:" + DEFAULT_TIME_ZONE + "}") String timeZone) {
        ZoneId zone = ZoneId.of(timeZone);
        log.info("App time zone: {}", zone);
        return Clock.system(zone);
    }
}

package com.university.docassistant.service;

import com.university.docassistant.exception.ApiException;
import com.university.docassistant.exception.ErrorCodes;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * US-14 AC-5: per-user limits on questions to the AI, so one user cannot use up the shared AI quota (Groq's free tier
 * counts tokens per minute and per day for the whole project). Every question counts - to the assistant, a course
 * chat or a document chat - from the moment it is accepted; a question the AI could not answer (503) is given back.
 * <p>
 * {@code app.ai.questions-per-hour} (default 30) and {@code app.ai.questions-per-day} (default 200) are sliding
 * windows: the last hour and the last 24 hours. 0 turns a limit off. Over a limit: 429 AI_QUESTION_LIMIT with the
 * time the next question can be asked and Retry-After. The counts live in memory, per instance, and start again after
 * a restart: a guard for the quota, not an accounting of questions.
 */
@Slf4j
@Component
public class AiQuestionLimiter {

    static final Duration HOUR = Duration.ofHours(1);
    static final Duration DAY = Duration.ofDays(1);

    private final int perHour;
    private final int perDay;
    private final Clock clock;
    /** Per user: the times of their accepted questions in the last 24 hours, oldest first. */
    private final Map<Long, Deque<Instant>> questions = new ConcurrentHashMap<>();

    public AiQuestionLimiter(@Value("${app.ai.questions-per-hour:30}") int perHour,
                             @Value("${app.ai.questions-per-day:200}") int perDay,
                             Clock clock) {
        this.perHour = Math.max(0, perHour);
        this.perDay = Math.max(0, perDay);
        this.clock = clock;
    }

    /** A counted question; {@link #giveBack()} undoes it when the AI could not answer. */
    public interface Permit {
        void giveBack();
    }

    private static final Permit NOTHING = () -> {
    };

    public int perHour() {
        return perHour;
    }

    public int perDay() {
        return perDay;
    }

    /** Counts a question of {@code userId}, or 429 AI_QUESTION_LIMIT when it would go over a limit. */
    public Permit acquire(Long userId) {
        if (userId == null || (perHour == 0 && perDay == 0)) {
            return NOTHING;
        }
        Instant now = clock.instant();
        Deque<Instant> times = questions.computeIfAbsent(userId, id -> new ArrayDeque<>());
        synchronized (times) {
            Instant dayAgo = now.minus(DAY);
            while (!times.isEmpty() && !times.peekFirst().isAfter(dayAgo)) {
                times.pollFirst();
            }
            if (perDay > 0 && times.size() >= perDay) {
                // The oldest of the day's questions leaves the window first.
                throw limit(userId, perDay, false, Duration.between(now, times.peekFirst().plus(DAY)));
            }
            if (perHour > 0) {
                Instant hourAgo = now.minus(HOUR);
                int inHour = 0;
                Instant oldestInHour = null;
                for (Iterator<Instant> it = times.descendingIterator(); it.hasNext(); ) {
                    Instant time = it.next();
                    if (!time.isAfter(hourAgo)) {
                        break;
                    }
                    inHour++;
                    oldestInHour = time;
                }
                if (inHour >= perHour) {
                    throw limit(userId, perHour, true, Duration.between(now, oldestInHour.plus(HOUR)));
                }
            }
            times.addLast(now);
        }
        return () -> giveBack(userId, now);
    }

    /** Remaining questions in the current hour and day; -1 for a limit that is off. */
    public Remaining remaining(Long userId) {
        Instant now = clock.instant();
        Deque<Instant> times = questions.get(userId);
        int inHour = 0;
        int inDay = 0;
        if (times != null) {
            synchronized (times) {
                for (Instant time : times) {
                    if (time.isAfter(now.minus(DAY))) {
                        inDay++;
                        if (time.isAfter(now.minus(HOUR))) {
                            inHour++;
                        }
                    }
                }
            }
        }
        return new Remaining(perHour == 0 ? -1 : Math.max(0, perHour - inHour),
                perDay == 0 ? -1 : Math.max(0, perDay - inDay), perHour, perDay);
    }

    public record Remaining(int hour, int day, int perHour, int perDay) {
    }

    private void giveBack(Long userId, Instant time) {
        Deque<Instant> times = questions.get(userId);
        if (times != null) {
            synchronized (times) {
                times.removeLastOccurrence(time);
            }
        }
    }

    private ApiException limit(Long userId, int limit, boolean hourly, Duration wait) {
        long seconds = Math.max(1, wait.toSeconds() + (wait.toNanosPart() > 0 ? 1 : 0));
        long minutes = Math.max(1, (seconds + 59) / 60);
        log.info("User {} reached the {} AI question limit ({})", userId, hourly ? "hourly" : "daily", limit);
        String message = "Лимит вопросов к ИИ: " + limit + (hourly ? " в час" : " в сутки")
                + ". Следующий вопрос можно задать через " + waitText(minutes) + ".";
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, message, ErrorCodes.AI_QUESTION_LIMIT, seconds);
    }

    /** "1 мин", "45 мин", "2 ч 5 мин". */
    static String waitText(long minutes) {
        if (minutes < 60) {
            return minutes + " мин";
        }
        long hours = minutes / 60;
        long rest = minutes % 60;
        return hours + " ч" + (rest == 0 ? "" : " " + rest + " мин");
    }
}

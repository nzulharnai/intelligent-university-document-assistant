package com.university.docassistant.service;

import com.university.docassistant.exception.ApiException;
import com.university.docassistant.exception.ErrorCodes;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/** US-14 AC-5: the per-user question limits, on a clock the test moves. */
class AiQuestionLimiterTest {

    private static final class MovableClock extends Clock {
        private Instant now = Instant.parse("2026-11-16T10:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final MovableClock clock = new MovableClock();

    @Test
    void theHourlyLimitStopsTheNextQuestionAndSaysWhenToAskAgain() {
        AiQuestionLimiter limiter = new AiQuestionLimiter(3, 100, clock);
        limiter.acquire(1L);
        clock.advance(Duration.ofMinutes(10));
        limiter.acquire(1L);
        limiter.acquire(1L);

        ApiException limit = catchThrowableOfType(() -> limiter.acquire(1L), ApiException.class);

        assertThat(limit.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(limit.getCode()).isEqualTo(ErrorCodes.AI_QUESTION_LIMIT);
        // The first question leaves the hour 50 minutes from now.
        assertThat(limit.getRetryAfterSeconds()).isEqualTo(50L * 60);
        assertThat(limit.getMessage()).isEqualTo(
                "Лимит вопросов к ИИ: 3 в час. Следующий вопрос можно задать через 50 мин.");
        // Another user is not affected.
        limiter.acquire(2L);

        clock.advance(Duration.ofMinutes(50));
        limiter.acquire(1L);
        assertThat(limiter.remaining(1L).hour()).isZero();
    }

    @Test
    void theDailyLimitCountsTheLast24Hours() {
        AiQuestionLimiter limiter = new AiQuestionLimiter(0, 2, clock);
        limiter.acquire(1L);
        clock.advance(Duration.ofHours(5));
        limiter.acquire(1L);

        ApiException limit = catchThrowableOfType(() -> limiter.acquire(1L), ApiException.class);
        assertThat(limit.getMessage()).isEqualTo(
                "Лимит вопросов к ИИ: 2 в сутки. Следующий вопрос можно задать через 19 ч.");
        assertThat(limiter.remaining(1L)).isEqualTo(new AiQuestionLimiter.Remaining(-1, 0, 0, 2));

        clock.advance(Duration.ofHours(19));
        limiter.acquire(1L);
    }

    @Test
    void aQuestionGivenBackDoesNotCount() {
        AiQuestionLimiter limiter = new AiQuestionLimiter(1, 0, clock);
        limiter.acquire(1L).giveBack();
        limiter.acquire(1L);
        assertThatThrownBy(() -> limiter.acquire(1L)).isInstanceOf(ApiException.class);
    }

    @Test
    void zeroTurnsTheLimitsOff() {
        AiQuestionLimiter limiter = new AiQuestionLimiter(0, 0, clock);
        for (int i = 0; i < 500; i++) {
            limiter.acquire(1L);
        }
        assertThat(limiter.remaining(1L)).isEqualTo(new AiQuestionLimiter.Remaining(-1, -1, 0, 0));
    }

    @Test
    void waitTextUsesHoursForLongWaits() {
        assertThat(AiQuestionLimiter.waitText(1)).isEqualTo("1 мин");
        assertThat(AiQuestionLimiter.waitText(60)).isEqualTo("1 ч");
        assertThat(AiQuestionLimiter.waitText(125)).isEqualTo("2 ч 5 мин");
    }
}

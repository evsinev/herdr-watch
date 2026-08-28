package com.payneteasy.herdrwatch.usage.pull;

import com.payneteasy.herdrwatch.usage.pull.PollPolicy.State;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Единственный по-настоящему опасный режим отказа этого источника — сам себя
 * заблокировать: повтор внутри наказания его перевзводит. Поэтому политика —
 * чистая функция, и проверяется как обычная логика.
 */
class PollPolicyTest {

    private static final Duration POLL = Duration.ofMinutes(5);

    private static PollPolicy policy() {
        return new PollPolicy(POLL, Duration.ofMinutes(1), Duration.ofHours(2), Duration.ofSeconds(30));
    }

    @Test
    void healthyPollsAtTheConfiguredInterval() {
        assertEquals(POLL, policy().nextDelay(State.fresh(), null));
    }

    @Test
    void serverRetryAfterBeatsOurOwnSchedule() {
        State s = State.fresh().after(PullOutcome.Result.rateLimited(Duration.ofSeconds(1300), "429"));

        Duration delay = policy().nextDelay(s, Duration.ofSeconds(1300));

        assertEquals(Duration.ofSeconds(1330), delay, "наказание плюс запас");
        assertTrue(delay.compareTo(POLL) > 0, "серверный Retry-After должен пересиливать наш интервал");
    }

    @Test
    void absurdRetryAfterIsCapped() {
        State s = State.fresh().after(PullOutcome.Result.rateLimited(Duration.ofDays(30), "429"));
        Duration delay = policy().nextDelay(s, Duration.ofDays(30));
        assertEquals(PollPolicy.RETRY_AFTER_CEILING.plusSeconds(30), delay);
    }

    @Test
    void repeatedRateLimitWithoutRetryAfterBacksOffExponentially() {
        PollPolicy p = policy();
        State s = State.fresh();
        Duration previous = Duration.ZERO;
        for (int i = 0; i < 6; i++) {
            s = s.after(PullOutcome.Result.of(PullOutcome.RATE_LIMITED, "429"));
            Duration delay = p.nextDelay(s, null);
            assertTrue(delay.compareTo(previous) >= 0, "задержка не должна уменьшаться");
            previous = delay;
        }
        assertTrue(previous.compareTo(Duration.ofMinutes(1)) > 0, "после шести отказов ждём заметно дольше пола");
    }

    @Test
    void backoffIsCappedButTheCapExceedsThePenalty() {
        PollPolicy p = policy();
        State s = State.fresh();
        for (int i = 0; i < 20; i++) s = s.after(PullOutcome.Result.of(PullOutcome.RATE_LIMITED, "429"));

        Duration delay = p.nextDelay(s, null);

        assertEquals(p.backoffCap(), delay);
        assertTrue(p.backoffCap().compareTo(PollPolicy.OBSERVED_PENALTY) > 0,
                "потолок ниже наказания = источник не выберется никогда");
    }

    @Test
    void aCapBelowThePenaltyIsRejectedAtConstruction() {
        // Это ловушка, а не вкусовщина: с таким потолком повтор всегда попадает внутрь
        // наказания и перевзводит его.
        assertThrows(IllegalArgumentException.class,
                () -> new PollPolicy(POLL, Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofSeconds(30)));
    }

    @Test
    void successResetsToTheNormalInterval() {
        PollPolicy p = policy();
        State s = State.fresh();
        for (int i = 0; i < 5; i++) s = s.after(PullOutcome.Result.of(PullOutcome.RATE_LIMITED, "429"));
        assertTrue(p.nextDelay(s, null).compareTo(POLL) > 0);

        s = s.after(PullOutcome.Result.ok());

        assertEquals(POLL, p.nextDelay(s, null));
        assertEquals(0, s.consecutiveRateLimits());
    }

    @Test
    void forbiddenIsNotRetriedAtTheNormalCadence() {
        // 403 = у токена нет user:profile. Само не пройдёт, частить бессмысленно.
        State s = State.fresh().after(PullOutcome.Result.of(PullOutcome.FORBIDDEN, "403"));

        Duration delay = policy().nextDelay(s, null);

        assertEquals(PollPolicy.FORBIDDEN_INTERVAL, delay);
        assertTrue(delay.compareTo(POLL) > 0);
    }

    @Test
    void networkFailureRetriesGentlyNotFaster() {
        State s = State.fresh().after(PullOutcome.Result.of(PullOutcome.OFFLINE, "no route"));
        assertEquals(POLL, policy().nextDelay(s, null), "сбой сети не повод частить");
    }

    @Test
    void rateLimitStreakIsBrokenByAnyOtherOutcome() {
        State s = State.fresh()
                .after(PullOutcome.Result.of(PullOutcome.RATE_LIMITED, "429"))
                .after(PullOutcome.Result.of(PullOutcome.OFFLINE, "down"));
        assertEquals(0, s.consecutiveRateLimits());
    }
}

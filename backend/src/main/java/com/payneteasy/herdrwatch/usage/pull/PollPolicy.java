package com.payneteasy.herdrwatch.usage.pull;

import java.time.Duration;

/**
 * Когда стучаться в аккаунт-API в следующий раз. Чистая функция от (настройки,
 * состояние) — никакого ввода-вывода, чтобы единственный опасный режим отказа
 * тестировался как обычная логика.
 *
 * <p>Опасность конкретная: эндпоинт отвечает на превышение наказанием в десятки
 * минут, а повтор ВНУТРИ наказания его перевзводит. Отсюда два инварианта:
 * серверный {@code Retry-After} всегда бьёт наше расписание, а потолок бэкоффа
 * обязан превышать наказание — иначе источник не выберется никогда (дизайн D5).
 */
public record PollPolicy(Duration pollInterval,
                         Duration backoffFloor,
                         Duration backoffCap,
                         Duration retryMargin) {

    /** Самое длинное наблюдавшееся наказание эндпоинта; потолок обязан быть больше. */
    public static final Duration OBSERVED_PENALTY = Duration.ofSeconds(1300);

    /** Санитарный предел на серверный Retry-After — на случай бессмыслицы в заголовке. */
    public static final Duration RETRY_AFTER_CEILING = Duration.ofHours(2);

    /** 403 сам не пройдёт: проверяем изредка, а не в обычном темпе. */
    public static final Duration FORBIDDEN_INTERVAL = Duration.ofHours(1);

    public PollPolicy {
        if (backoffCap.compareTo(OBSERVED_PENALTY) <= 0) {
            throw new IllegalArgumentException(
                    "backoff cap " + backoffCap + " must exceed the observed penalty "
                            + OBSERVED_PENALTY + ", otherwise the source never recovers");
        }
    }

    /** Состояние между попытками. */
    public record State(int consecutiveRateLimits, int consecutiveFailures, PullOutcome last) {
        public static State fresh() {
            return new State(0, 0, null);
        }

        public State after(PullOutcome.Result r) {
            if (r.outcome() == PullOutcome.OK) return fresh();
            if (r.outcome() == PullOutcome.RATE_LIMITED) {
                return new State(consecutiveRateLimits + 1, consecutiveFailures + 1, r.outcome());
            }
            return new State(0, consecutiveFailures + 1, r.outcome());
        }
    }

    /** Задержка до следующей попытки. */
    public Duration nextDelay(State state, Duration serverRetryAfter) {
        if (state.consecutiveRateLimits() > 0) {
            // Слово сервера сильнее нашего расписания: ждём всё наказание плюс запас.
            if (serverRetryAfter != null && !serverRetryAfter.isNegative() && !serverRetryAfter.isZero()) {
                Duration wait = min(serverRetryAfter, RETRY_AFTER_CEILING).plus(retryMargin);
                return max(wait, backoffFloor);
            }
            // Без Retry-After — экспонента от пола, с потолком.
            int exponent = Math.min(state.consecutiveRateLimits() - 1, 8);
            Duration backoff = backoffFloor.multipliedBy(1L << exponent);
            return min(backoff, backoffCap);
        }
        if (state.last() == PullOutcome.FORBIDDEN) return FORBIDDEN_INTERVAL;
        if (state.consecutiveFailures() > 0) {
            // Сеть и прочее — спокойный повтор, но не чаще обычного темпа.
            return pollInterval;
        }
        return pollInterval;
    }

    private static Duration min(Duration a, Duration b) {
        return a.compareTo(b) <= 0 ? a : b;
    }

    private static Duration max(Duration a, Duration b) {
        return a.compareTo(b) >= 0 ? a : b;
    }
}

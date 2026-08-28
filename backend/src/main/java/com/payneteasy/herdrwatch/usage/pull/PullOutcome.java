package com.payneteasy.herdrwatch.usage.pull;

import java.time.Duration;

/**
 * Исход одного обращения к аккаунт-API. Разведены намеренно: политика опроса
 * реагирует на них по-разному, и оператору они означают разное.
 */
public enum PullOutcome {
    /** 200 и тело разобрано. */
    OK,
    /** 401 — токен протух; перечитать хранилище на следующем тике, не «чинить» его. */
    UNAUTHORIZED,
    /** 403 — у токена нет user:profile; само не пройдёт, частить бессмысленно. */
    FORBIDDEN,
    /** 429 — наказание; повтор внутри окна его перевзводит. */
    RATE_LIMITED,
    /** Сеть недоступна. */
    OFFLINE,
    /** 200, но тело не разобрано, либо неожиданный статус — форма поехала. */
    SCHEMA_CHANGED,
    /** Креденшла нет или он непригоден — запрос даже не делался. */
    NO_CREDENTIAL;

    public boolean isFailure() {
        return this != OK;
    }

    /** Ответ сервера с необязательным Retry-After. */
    public record Result(PullOutcome outcome, Duration retryAfter, String detail) {
        public static Result ok() {
            return new Result(OK, null, null);
        }

        public static Result of(PullOutcome outcome, String detail) {
            return new Result(outcome, null, detail);
        }

        public static Result rateLimited(Duration retryAfter, String detail) {
            return new Result(RATE_LIMITED, retryAfter, detail);
        }
    }
}

package com.payneteasy.herdrwatch.usage.pull;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * OAuth-креденшл, которым владеет Claude Code. Мы его ТОЛЬКО читаем: ротация —
 * его дело, а не наше (дизайн D4).
 *
 * <p>{@code expiresAt} в исходном JSON — миллисекунды. Прочитать их как секунды
 * значит объявить любой живой токен древним; отдельная ловушка, отдельный тест.
 */
public record ClaudeCredential(String accessToken, Instant expiresAt, Set<String> scopes) {

    /** Скоуп, без которого usage-эндпоинт отвечает 403 (setup-token'ы его не дают). */
    public static final String PROFILE_SCOPE = "user:profile";

    public ClaudeCredential {
        scopes = scopes == null ? Set.of() : Set.copyOf(scopes);
    }

    public static ClaudeCredential of(String accessToken, Long expiresAtMillis, List<String> scopes) {
        return new ClaudeCredential(
                accessToken,
                expiresAtMillis == null ? null : Instant.ofEpochMilli(expiresAtMillis),
                scopes == null ? Set.of() : Set.copyOf(scopes));
    }

    public boolean hasProfileScope() {
        return scopes.contains(PROFILE_SCOPE);
    }

    /** Токен без срока считаем живым, пока API не скажет 401. */
    public boolean isExpired(Instant now) {
        return expiresAt != null && !now.isBefore(expiresAt);
    }

    /**
     * Токен НИКОГДА не должен попасть в лог — в том числе через автосгенерированный
     * {@code toString()} record'а, который иначе печатает все поля.
     */
    @Override
    public String toString() {
        return "ClaudeCredential[expiresAt=" + expiresAt + ", scopes=" + scopes + ", token=***]";
    }
}

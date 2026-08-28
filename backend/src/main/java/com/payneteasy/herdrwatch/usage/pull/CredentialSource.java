package com.payneteasy.herdrwatch.usage.pull;

import java.time.Instant;
import java.util.List;

/**
 * Источник OAuth-креденшла Claude Code. Только чтение — ни один метод не имеет
 * права писать, обновлять или удалять запись (дизайн D4, спека).
 */
public interface CredentialSource {

    /** Все кандидаты, которые удалось прочитать. Пустой список = здесь ничего нет. */
    List<ClaudeCredential> candidates() throws CredentialAccessException;

    /** Человекочитаемое имя для логов и диагностики. */
    String describe();

    /**
     * Лучший кандидат: живой и с {@code user:profile}. Из нескольких подходящих —
     * тот, что протухнет позже всех.
     *
     * <p>Именно здесь чинится ошибка, из-за которой pull-ветку когда-то закрыли:
     * брать «первый попавшийся из хранилища» нельзя — под одним service лежало два
     * элемента, и первым отдавался мёртвый.
     */
    static CredentialLookup best(List<ClaudeCredential> candidates, Instant now) {
        if (candidates.isEmpty()) return CredentialLookup.notConfigured("no credential found");

        ClaudeCredential best = null;
        boolean sawLive = false;
        for (ClaudeCredential c : candidates) {
            if (c.isExpired(now)) continue;
            sawLive = true;
            if (!c.hasProfileScope()) continue;
            if (best == null || laterExpiry(c, best)) best = c;
        }
        if (best != null) return CredentialLookup.found(best);
        if (sawLive) {
            return CredentialLookup.notAuthorized(
                    "credential has no " + ClaudeCredential.PROFILE_SCOPE + " scope");
        }
        return CredentialLookup.notConfigured("all " + candidates.size() + " credential(s) expired");
    }

    private static boolean laterExpiry(ClaudeCredential a, ClaudeCredential b) {
        if (a.expiresAt() == null) return true;    // без срока — считаем самым долгоживущим
        if (b.expiresAt() == null) return false;
        return a.expiresAt().isAfter(b.expiresAt());
    }
}

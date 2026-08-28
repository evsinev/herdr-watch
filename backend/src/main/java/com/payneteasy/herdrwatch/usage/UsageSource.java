package com.payneteasy.herdrwatch.usage;

/**
 * Кто наблюдал показания. Никогда не null — спека «Report which source produced the
 * figures»: у одних и тех же цифры с одним и тем же возрастом разный смысл в
 * зависимости от того, кто их видел.
 */
public enum UsageSource {
    /** Показаний ещё не было ни от кого. */
    NONE,
    /** statusline-хук Claude Code (push). */
    STATUSLINE,
    /** Аккаунт-API Anthropic (pull). */
    ACCOUNT_API
}

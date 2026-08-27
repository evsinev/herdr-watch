package com.payneteasy.herdrwatch.usage;

/**
 * Пороги «полосы» утилизации квоты — ЕДИНСТВЕННЫЙ источник для двух потребителей:
 * {@code severityCode} в Snapshot API (§3.5, дизайн D8) и цветовые полосы в UI.
 *
 * <p>Фронтенд повторяет эти же числа в {@code frontend/src/lib/theme.ts}
 * ({@code USAGE_BANDS}) — язык другой, значения обязаны совпадать; правим парой.
 */
public final class UsageSeverity {

    private UsageSeverity() {}

    /** Код: состояние неизвестно (нет записи или ни одно окно не отчиталось). */
    public static final int UNKNOWN = 0;
    public static final int OK = 1;
    public static final int WARNING = 2;
    public static final int CRITICAL = 3;
    public static final int EXHAUSTED = 4;

    /** С этого процента — WARNING. */
    public static final int WARN_AT = 70;
    /** С этого процента — CRITICAL. */
    public static final int CRITICAL_AT = 90;
    /** С этого процента — EXHAUSTED (окно выбрано полностью). */
    public static final int EXHAUSTED_AT = 100;

    /** Полоса одного окна по его утилизации. */
    public static int ofPercent(int usedPercent) {
        if (usedPercent >= EXHAUSTED_AT) return EXHAUSTED;
        if (usedPercent >= CRITICAL_AT) return CRITICAL;
        if (usedPercent >= WARN_AT) return WARNING;
        return OK;
    }

    /**
     * Код снапшота: худшее из отчитавшихся окон. Если не отчиталось ни одного
     * (в т.ч. NOT_CONFIGURED) — {@link #UNKNOWN}: полосу неоткуда взять.
     *
     * <p>STALE сам по себе полосу не занижает — цифры остаются лучшим, что у нас есть;
     * о свежести клиенту говорит {@code state} и {@code capturedAt}, а не этот код.
     */
    public static int of(ClaudeUsage usage) {
        if (usage == null || usage.windows() == null) return UNKNOWN;
        int worst = UNKNOWN;
        ClaudeUsage.Window[] all = { usage.windows().fiveHour(), usage.windows().sevenDay() };
        for (ClaudeUsage.Window w : all) {
            if (w != null) worst = Math.max(worst, ofPercent(w.usedPercent()));
        }
        return worst;
    }
}

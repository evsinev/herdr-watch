package com.payneteasy.herdrwatch.usage;

import com.fasterxml.jackson.annotation.JsonIgnore;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Снапшот квоты подписки Claude — то, что герой этой фичи отдаёт наружу
 * (SSE {@code claude_usage}, {@code GET /api/claude-usage}).
 *
 * <p>Дизайн D6: отсутствие моделируется {@code null}, а не нулём. Окно, которого нет в
 * записи, — {@code null} целиком, чтобы «не отчитывались» нельзя было спутать с «0%».
 * Состояния ровно три: {@link State#NOT_CONFIGURED} (файла никогда не было),
 * {@link State#OK} и {@link State#STALE} (запись есть, но старая или нечитаемая).
 * Отдельного ERROR нет: нечитаемый файл при наличии прошлого снапшота — это STALE
 * с причиной, а без прошлого — NOT_CONFIGURED (нормальное состояние до установки хука).
 *
 * <p>Внимание: у Snapshot API своё представление ({@code SnapshotUsage}) — там null
 * запрещён контрактом (§3.4).
 */
@Schema(name = "ClaudeUsage", description = "Квота подписки Claude (аккаунт целиком)")
public record ClaudeUsage(
        @Schema(required = true, description = "NOT_CONFIGURED | OK | STALE")
        State state,
        @Schema(description = "unix-время снятия показаний; null, если записи никогда не было")
        Long capturedAt,
        @Schema(description = "причина деградации; null, когда её нет")
        String error,
        @Schema(required = true, description = "окна квоты; каждое может быть null (не отчитывалось)")
        Windows windows
) {

    /** Состояние снапшота. */
    public enum State { NOT_CONFIGURED, OK, STALE }

    /** Одно окно квоты. */
    @Schema(name = "ClaudeUsageWindow", description = "Одно окно квоты")
    public record Window(
            @Schema(required = true, description = "утилизация, целые проценты 0..100")
            int usedPercent,
            @Schema(required = true, description = "unix-время сброса окна")
            long resetsAt
    ) {}

    /** Пара окон; null-поле = окно не отчитывалось (не 0%). */
    @Schema(name = "ClaudeUsageWindows", description = "Окна квоты; null = окно не отчитывалось")
    public record Windows(
            @Schema(description = "5-часовое сессионное окно")
            Window fiveHour,
            @Schema(description = "недельное окно")
            Window sevenDay
    ) {
        @JsonIgnore   // производное; в JSON ему делать нечего (Jackson тянет is*() как поле)
        @Schema(hidden = true)
        public boolean isEmpty() {
            return fiveHour == null && sevenDay == null;
        }
    }

    private static final Windows NO_WINDOWS = new Windows(null, null);

    /** Хук ещё не ставили (или он ни разу не отработал): не ошибка, а норма. */
    public static ClaudeUsage notConfigured() {
        return new ClaudeUsage(State.NOT_CONFIGURED, null, null, NO_WINDOWS);
    }

    public static ClaudeUsage ok(long capturedAt, Window fiveHour, Window sevenDay) {
        return new ClaudeUsage(State.OK, capturedAt, null, new Windows(fiveHour, sevenDay));
    }

    /** Тот же снапшот, помеченный устаревшим. Цифры и capturedAt сохраняются. */
    public ClaudeUsage stale(String reason) {
        return new ClaudeUsage(State.STALE, capturedAt, reason, windows);
    }
}

package com.payneteasy.herdrwatch.usage;

import com.fasterxml.jackson.annotation.JsonIgnore;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;

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
        @Schema(required = true, description = "кто наблюдал показания: NONE | STATUSLINE | ACCOUNT_API")
        UsageSource source,
        @Schema(description = "unix-время снятия показаний; null, если записи никогда не было")
        Long capturedAt,
        @Schema(description = "причина деградации; null, когда её нет")
        String error,
        @Schema(required = true, description = "окна квоты; каждое может быть null (не отчитывалось)")
        Windows windows,
        @Schema(required = true, description = "помодельные недельные окна; пусто, если их не отдали")
        List<ModelWindow> models
) {

    public ClaudeUsage {
        models = models == null ? List.of() : List.copyOf(models);
        source = source == null ? UsageSource.NONE : source;
    }

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

    /**
     * Одно помодельное недельное окно. Набор моделей ОТКРЫТ: имя носим как есть,
     * не отображая в enum, иначе незнакомая модель молча исчезнет по дороге.
     */
    @Schema(name = "ClaudeUsageModelWindow", description = "Недельное окно, привязанное к модели")
    public record ModelWindow(
            @Schema(required = true, description = "имя модели, как его прислал сервер")
            String model,
            @Schema(required = true, description = "утилизация, целые проценты 0..100")
            int usedPercent,
            @Schema(required = true, description = "unix-время сброса окна")
            long resetsAt
    ) {}

    private static final Windows NO_WINDOWS = new Windows(null, null);

    /** Источник ещё ничего не дал: не ошибка, а норма (хук не ставили / pull выключен). */
    public static ClaudeUsage notConfigured(UsageSource source) {
        return new ClaudeUsage(State.NOT_CONFIGURED, source, null, null, NO_WINDOWS, List.of());
    }

    /** Стартовое состояние Registry: показаний не было ни от кого. */
    public static ClaudeUsage none() {
        return notConfigured(UsageSource.NONE);
    }

    public static ClaudeUsage ok(UsageSource source, long capturedAt,
                                 Window fiveHour, Window sevenDay, List<ModelWindow> models) {
        return new ClaudeUsage(State.OK, source, capturedAt, null,
                new Windows(fiveHour, sevenDay), models);
    }

    /** Тот же снапшот, помеченный устаревшим. Цифры, источник и capturedAt сохраняются. */
    public ClaudeUsage stale(String reason) {
        return new ClaudeUsage(State.STALE, source, capturedAt, reason, windows, models);
    }

    /**
     * Тот же снапшот с подставленными помодельными окнами.
     *
     * <p>Нужно потому, что помодельные окна отдаёт ТОЛЬКО аккаунт-API, а публикуемое
     * показание выбирается по свежести: под {@code auto} statusline обновляется чаще
     * (на каждое движение цифр) и почти всегда выигрывает у пятиминутного опроса. Без
     * переноса строка модели мигала бы, появляясь на секунды раз в пять минут.
     */
    public ClaudeUsage withModels(List<ModelWindow> models) {
        return new ClaudeUsage(state, source, capturedAt, error, windows, models);
    }
}

package com.payneteasy.herdrwatch.snapshot;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;

/**
 * Ответ {@code GET /api/v1/snapshot/usage} — квота подписки Claude для встраиваемых
 * клиентов (дизайн D7/D8). Совместимое дополнение по §7: отдельный эндпоинт, состав
 * существующих профилей {@code view} не тронут, {@code protocolVersion} остаётся 1.
 *
 * <p>Контрактные отличия от внутренней модели {@code ClaudeUsage}: null запрещён (§3.4),
 * поэтому неотчитавшееся окно ПРОПУСКАЕТСЯ в массиве (а не приходит нулём или null),
 * а неизвестное время снятия — это 0. Полоса отдаётся одним целым {@code severityCode}
 * в идиоме {@code statusCode} (§3.5): клиенту со светодиодом хватит его одного.
 */
@Schema(name = "SnapshotUsage", description = "Квота подписки Claude (аккаунт целиком)")
public record SnapshotUsage(
        @Schema(required = true, description = "версия контракта, сейчас 1")
        int protocolVersion,
        @Schema(required = true, description = "OK | STALE | NOT_CONFIGURED")
        String state,
        @Schema(required = true,
                description = "0 неизвестно/не настроено, 1 ok, 2 warning, 3 critical, 4 exhausted")
        int severityCode,
        @Schema(required = true, description = "unix-время снятия показаний; 0, если показаний не было")
        long capturedAt,
        @Schema(required = true, description = "кто наблюдал показания: NONE | STATUSLINE | ACCOUNT_API")
        String source,
        @Schema(required = true,
                description = "по записи на каждое отчитавшееся окно; пустой массив допустим, null — нет")
        List<Window> windows,
        @Schema(required = true,
                description = "помодельные недельные окна; пустой массив, если их не отдали")
        List<ModelWindow> models
) {

    /** Помодельное недельное окно. Набор моделей открыт — имя приходит как есть (§6). */
    @Schema(name = "SnapshotUsageModelWindow", description = "Недельное окно, привязанное к модели")
    public record ModelWindow(
            @Schema(required = true, description = "имя модели, как его прислал сервер")
            String model,
            @Schema(required = true, description = "утилизация, целые проценты 0..100")
            int usedPercent,
            @Schema(required = true, description = "unix-время сброса окна")
            long resetsAt
    ) {}

    /** Одно окно квоты. Неотчитавшееся окно в массив не попадает вовсе (§3.4). */
    @Schema(name = "SnapshotUsageWindow", description = "Одно окно квоты")
    public record Window(
            @Schema(required = true, description = "five_hour | seven_day")
            String type,
            @Schema(required = true, description = "утилизация, целые проценты 0..100")
            int usedPercent,
            @Schema(required = true, description = "unix-время сброса окна")
            long resetsAt
    ) {}
}

package com.payneteasy.herdrwatch.usage.pull;

import com.payneteasy.herdrwatch.usage.ClaudeUsage;
import com.payneteasy.herdrwatch.usage.Percents;
import com.payneteasy.herdrwatch.usage.UsageSource;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * Ответ аккаунт-API → {@link ClaudeUsage}.
 *
 * <p>Форма отличается от statusline-payload'а: {@code utilization} — дробное,
 * {@code resets_at} — ISO-8601 строка. Приводим на краю к тем же целым процентам и
 * epoch-секундам ({@link Percents}), которыми пользуется push-источник: два источника
 * не должны расходиться из-за соглашения об округлении.
 *
 * <p>{@code limits[]} предпочитаем легаси-ключам {@code seven_day_<model>}: на живом
 * аккаунте все они {@code null}, а помодельные окна лежат только в массиве.
 */
public final class UsageResponseMapper {

    private UsageResponseMapper() {}

    private static final String KIND_SESSION = "session";
    private static final String KIND_WEEKLY_ALL = "weekly_all";
    private static final String KIND_WEEKLY_SCOPED = "weekly_scoped";

    /** null — тело непригодно (форма поехала). */
    public static ClaudeUsage map(JsonNode root, Instant capturedAt) {
        if (root == null || !root.isObject()) return null;

        ClaudeUsage.Window fiveHour = null;
        ClaudeUsage.Window sevenDay = null;
        List<ClaudeUsage.ModelWindow> models = new ArrayList<>();

        JsonNode limits = root.get("limits");
        if (limits != null && limits.isArray() && !limits.isEmpty()) {
            for (JsonNode entry : limits) {
                String kind = text(entry.get("kind"));
                Integer percent = percent(entry.get("percent"));
                Long resets = epoch(text(entry.get("resets_at")));
                if (kind == null || percent == null || resets == null) continue;

                switch (kind) {
                    case KIND_SESSION -> fiveHour = new ClaudeUsage.Window(percent, resets);
                    case KIND_WEEKLY_ALL -> sevenDay = new ClaudeUsage.Window(percent, resets);
                    case KIND_WEEKLY_SCOPED -> {
                        String model = modelName(entry);
                        // Имя модели носим как есть: набор открыт, и незнакомая модель
                        // должна дойти до UI, а не исчезнуть по дороге.
                        if (model != null) models.add(new ClaudeUsage.ModelWindow(model, percent, resets));
                    }
                    default -> { /* незнакомый kind игнорируем, не падаем */ }
                }
            }
        }

        // Легаси-форма: окна лежат в верхнем уровне как {utilization, resets_at}.
        if (fiveHour == null) fiveHour = topLevel(root.get("five_hour"));
        if (sevenDay == null) sevenDay = topLevel(root.get("seven_day"));

        if (fiveHour == null && sevenDay == null && models.isEmpty()) return null;

        return ClaudeUsage.ok(UsageSource.ACCOUNT_API, capturedAt.getEpochSecond(),
                fiveHour, sevenDay, models);
    }

    private static ClaudeUsage.Window topLevel(JsonNode n) {
        if (n == null || !n.isObject()) return null;
        Integer percent = percent(n.get("utilization"));
        Long resets = epoch(text(n.get("resets_at")));
        return (percent == null || resets == null) ? null : new ClaudeUsage.Window(percent, resets);
    }

    private static String modelName(JsonNode entry) {
        JsonNode scope = entry.get("scope");
        if (scope == null || !scope.isObject()) return null;
        JsonNode model = scope.get("model");
        if (model == null || !model.isObject()) return null;
        String name = text(model.get("display_name"));
        return (name == null || name.isBlank()) ? null : name;
    }

    private static Integer percent(JsonNode n) {
        return (n == null || !n.isNumber()) ? null : Percents.toWhole(n.doubleValue());
    }

    private static String text(JsonNode n) {
        return (n == null || !n.isTextual()) ? null : n.textValue();
    }

    /** ISO-8601 с дробными секундами и офсетом → epoch-секунды. */
    private static Long epoch(String iso) {
        if (iso == null) return null;
        try {
            long seconds = Instant.parse(iso.replace("+00:00", "Z")).getEpochSecond();
            return seconds > 0 ? seconds : null;
        } catch (DateTimeParseException e) {
            try {
                return java.time.OffsetDateTime.parse(iso).toInstant().getEpochSecond();
            } catch (DateTimeParseException e2) {
                return null;
            }
        }
    }
}

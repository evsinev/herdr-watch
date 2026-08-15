package com.payneteasy.herdrwatch.snapshot;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Запись агента для профиля {@code status} (§3.5) — индикаторы без текста,
 * агрегат {@code max(statusCode)}, health-check. Состав полей заморожен.
 */
@Schema(name = "SnapshotAgentStatus", description = "Минимальная запись агента (профиль status)")
public record SnapshotAgentStatus(
        @Schema(required = true)
        String host,
        @Schema(required = true)
        String project,
        @Schema(required = true)
        int statusCode,
        @Schema(required = true)
        boolean hostStale
) {}

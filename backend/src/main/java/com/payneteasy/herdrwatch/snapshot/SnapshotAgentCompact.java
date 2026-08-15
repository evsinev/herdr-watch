package com.payneteasy.herdrwatch.snapshot;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Запись агента для профиля {@code compact} (§3.5). Состав полей заморожен —
 * ровно то, что нужно для строки вида {@code dqa1 paynet-ui feat/mfe-split}.
 */
@Schema(name = "SnapshotAgentCompact", description = "Сокращённая запись агента (профиль compact)")
public record SnapshotAgentCompact(
        @Schema(required = true)
        String host,
        @Schema(required = true)
        String project,
        @Schema(required = true)
        String branch,
        @Schema(required = true)
        String agentName,
        @Schema(required = true)
        int statusCode,
        @Schema(required = true)
        boolean hostStale,
        @Schema(required = true)
        boolean detachedHead
) {}

package com.payneteasy.herdrwatch.snapshot;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** Ответ {@code GET /api/v1/snapshot/time} (§4). */
@Schema(name = "SnapshotTime", description = "Часовой пояс и смещение сервера")
public record SnapshotTime(
        @Schema(required = true, description = "версия контракта")
        int protocolVersion,
        @Schema(required = true, description = "ISO-8601 с офсетом, локальное время сервера")
        String serverTime,
        @Schema(required = true, description = "Unix-время, секунды UTC")
        long serverEpoch,
        @Schema(required = true, description = "IANA tz id сервера")
        String timeZone,
        @Schema(required = true, description = "смещение timeZone в секундах на момент serverEpoch, с учётом DST")
        int utcOffset
) {}

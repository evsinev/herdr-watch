package com.payneteasy.herdrwatch.snapshot;

import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Ответ {@code GET /api/v1/snapshot/agents?view=full} (§3.2/§3.3).
 * Профили реализованы отдельными record-типами (§8) → различимые схемы в OpenAPI.
 */
@Schema(name = "SnapshotResponseFull", description = "Снапшот агентов, профиль full")
public record SnapshotResponseFull(
        @Schema(required = true, description = "версия контракта, сейчас 1")
        int protocolVersion,
        @Schema(required = true, description = "монотонный счётчик состояния; входит в ETag")
        long sequence,
        @Schema(required = true, description = "длина agents после применения limit")
        int agentCount,
        @Schema(required = true, description = "общее число агентов до применения limit; >= agentCount")
        int agentTotal,
        @Schema(required = true, description = "может быть пустым, null не допускается")
        List<SnapshotAgent> agents
) {}

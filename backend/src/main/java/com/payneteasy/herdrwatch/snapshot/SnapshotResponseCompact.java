package com.payneteasy.herdrwatch.snapshot;

import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** Ответ {@code GET /api/v1/snapshot/agents?view=compact} (§3.2/§3.3). */
@Schema(name = "SnapshotResponseCompact", description = "Снапшот агентов, профиль compact")
public record SnapshotResponseCompact(
        @Schema(required = true)
        int protocolVersion,
        @Schema(required = true)
        long sequence,
        @Schema(required = true)
        int agentCount,
        @Schema(required = true)
        int agentTotal,
        @Schema(required = true)
        List<SnapshotAgentCompact> agents
) {}

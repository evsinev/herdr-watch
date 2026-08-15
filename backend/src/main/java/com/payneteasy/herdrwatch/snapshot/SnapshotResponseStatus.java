package com.payneteasy.herdrwatch.snapshot;

import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** Ответ {@code GET /api/v1/snapshot/agents?view=status} (§3.2/§3.3). */
@Schema(name = "SnapshotResponseStatus", description = "Снапшот агентов, профиль status")
public record SnapshotResponseStatus(
        @Schema(required = true)
        int protocolVersion,
        @Schema(required = true)
        long sequence,
        @Schema(required = true)
        int agentCount,
        @Schema(required = true)
        int agentTotal,
        @Schema(required = true)
        List<SnapshotAgentStatus> agents
) {}

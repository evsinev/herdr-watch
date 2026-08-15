package com.payneteasy.herdrwatch.snapshot;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Тело ошибки Snapshot API (§5). Отдельный конверт {@code {error, message}} —
 * не {@code {errors:{}}} из {@code ServersResource}: для этого API побеждает контракт.
 */
@Schema(name = "SnapshotError", description = "Тело ошибки Snapshot API")
public record SnapshotError(
        @Schema(required = true, description = "стабильный машиночитаемый код: invalid_parameter | not_ready | internal_error")
        String error,
        @Schema(required = true, description = "человекочитаемое пояснение; может меняться без смены версии")
        String message
) {}

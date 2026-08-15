package com.payneteasy.herdrwatch.snapshot;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Запись агента для профиля {@code full} (§3.4/§3.5 контракта).
 * Все поля присутствуют всегда, {@code null} не допускается: пустая строка / {@code false}.
 */
@Schema(name = "SnapshotAgent", description = "Полная запись агента (профиль full)")
public record SnapshotAgent(
        @Schema(required = true, description = "идентификатор хоста из конфигурации herdr-watch")
        String host,
        @Schema(required = true, description = "basename корня git-репозитория, иначе basename рабочего каталога")
        String project,
        @Schema(required = true, description = "имя ветки; в detached HEAD — сокращённый SHA")
        String branch,
        @Schema(required = true, description = "канонический идентификатор агента (claude, codex, ...); пусто — не распознан")
        String agentName,
        @Schema(required = true, description = "отображаемое имя агента")
        String agentDisplay,
        @Schema(required = true, description = "UNKNOWN|IDLE|WORKING|DONE|BLOCKED")
        String status,
        @Schema(required = true, description = "числовой эквивалент status, 0..4")
        int statusCode,
        @Schema(required = true, description = "хост недоступен, запись отражает последнее известное состояние")
        boolean hostStale,
        @Schema(required = true, description = "абсолютный путь чекаута на целевом хосте; пусто — не удалось определить")
        String worktreePath,
        @Schema(required = true, description = "метка воркстри в herdr; пусто — метки нет или это не воркстри")
        String worktreeLabel,
        @Schema(required = true, description = "true — linked worktree, false — основной чекаут")
        boolean linkedWorktree,
        @Schema(required = true, description = "HEAD отделён; при true branch содержит SHA")
        boolean detachedHead,
        @Schema(required = true, description = "git считает воркстри пригодным к git worktree prune")
        boolean prunable
) {}

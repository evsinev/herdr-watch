package com.payneteasy.herdrwatch.source;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

/**
 * Типизированные DTO сообщений herdr socket API (NDJSON) — вместо ручного обхода Jackson-дерева.
 * Каждое сообщение десериализуется прямо в record; на провод/с провода имена мапятся через
 * {@link JsonProperty} (herdr использует snake_case). Указываем только используемые поля —
 * остальные (panes/tabs/layouts/terminal_id/revision/…) игнорируются (mapper с ignore-unknown).
 *
 * Регистрируем record'ы для рефлексии здесь же: их (де)сериализует «ручной» ObjectMapper,
 * авто-регистрации quarkus-rest недостаточно (в JVM-режиме аннотация ни на что не влияет).
 */
@RegisterForReflection(targets = {
        HerdrProtocol.Request.class,
        HerdrProtocol.ErrorBody.class,
        HerdrProtocol.SnapshotResponse.class,
        HerdrProtocol.SnapshotResult.class,
        HerdrProtocol.Snapshot.class,
        HerdrProtocol.WorktreeResponse.class,
        HerdrProtocol.WorktreeResult.class,
        HerdrProtocol.WorktreeParams.class,
        HerdrProtocol.WorkspaceRec.class,
        HerdrProtocol.AgentRec.class,
        HerdrProtocol.WorktreeRec.class,
        HerdrProtocol.Subscription.class,
        HerdrProtocol.EventsSubscribeParams.class,
        HerdrProtocol.AckResponse.class,
})
final class HerdrProtocol {

    private HerdrProtocol() {}

    /** Общий mapper для socket-DTO: лишние поля herdr игнорируем, records поддерживаются нативно. */
    static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** Общее у всех ответов — для сверки id и проверки ошибки без обхода дерева. */
    interface HerdrResponse {
        String id();
        ErrorBody error();
    }

    // --- запросы ---

    record Request(String id, String method, Object params) {}

    record WorktreeParams(@JsonProperty("workspace_id") String workspaceId) {}

    // events.subscribe: массив подписок (нам достаточно type-only подписок — они триггерят re-snapshot)
    record EventsSubscribeParams(List<Subscription> subscriptions) {}

    record Subscription(String type) {}

    /** Ответ-ack на events.subscribe (result игнорируем, важны id и error). */
    record AckResponse(String id, ErrorBody error) implements HerdrResponse {}

    // --- общий конверт ---

    record ErrorBody(String code, String message) {}

    // --- session.snapshot ---

    record SnapshotResponse(String id, SnapshotResult result, ErrorBody error) implements HerdrResponse {}

    record SnapshotResult(String type, Snapshot snapshot) {}

    record Snapshot(
            String version,
            int protocol,
            List<WorkspaceRec> workspaces,
            List<AgentRec> agents
    ) {}

    // --- worktree.list ---

    record WorktreeResponse(String id, WorktreeResult result, ErrorBody error) implements HerdrResponse {}

    record WorktreeResult(String type, List<WorktreeRec> worktrees) {}

    // --- leaf-записи (ровно то, что маппится в доменную Model) ---

    record WorkspaceRec(
            @JsonProperty("workspace_id") String workspaceId,
            String label,
            Integer number,
            @JsonProperty("agent_status") String agentStatus,
            boolean focused,
            @JsonProperty("pane_count") int paneCount,
            @JsonProperty("tab_count") int tabCount
    ) {}

    record AgentRec(
            String agent,
            @JsonProperty("agent_status") String agentStatus,
            String cwd,
            boolean focused,
            @JsonProperty("pane_id") String paneId,
            @JsonProperty("tab_id") String tabId,
            @JsonProperty("terminal_title_stripped") String terminalTitleStripped,
            @JsonProperty("workspace_id") String workspaceId
    ) {}

    record WorktreeRec(
            String path,
            String branch,
            String label,
            @JsonProperty("is_detached") boolean isDetached,
            @JsonProperty("is_prunable") boolean isPrunable,
            @JsonProperty("is_linked_worktree") boolean isLinkedWorktree,
            @JsonProperty("open_workspace_id") String openWorkspaceId
    ) {}
}

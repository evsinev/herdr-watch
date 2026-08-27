package com.payneteasy.herdrwatch.model;

import java.util.List;

/**
 * Доменная модель дашборда. Всё immutable (record'ы) — эти объекты
 * сериализуются в JSON и рассылаются подписчикам SSE.
 */
public final class Model {

    private Model() {}

    /** Здоровье соединения с хостом. */
    public enum Health {
        CONNECTED,     // поток кадров идёт, herdr отвечает
        DEGRADED,      // ssh жив, но herdr вернул null (agent/workspace list не отработал)
        UNREACHABLE    // ssh недоступен / переподключаемся (аналог "⚪" из bash-скрипта)
    }

    /**
     * Один агент. Поля соответствуют объекту из `herdr agent list --json`:
     * agent, agent_status, cwd, focused, pane_id, tab_id, terminal_id,
     * terminal_title_stripped, workspace_id.
     * Поля `name` в выводе нет для вручную запущенных агентов — вместо него
     * для подписи используем terminal_title_stripped (имя ветки/задачи).
     */
    public record AgentInfo(
            String title,         // terminal_title_stripped — основная подпись
            String kind,          // "agent": pi / claude / codex / ...
            String status,        // agent_status: idle | working | blocked | done | unknown
            String workspaceId,
            String tabId,
            String paneId,
            boolean focused,
            String cwd
    ) {}

    /** Один git-worktree воркспейса из `herdr worktree list --workspace <id>`. */
    public record WorktreeInfo(
            String branch,
            String path,
            String label,
            boolean detached,
            boolean prunable,
            boolean linked,
            String openWorkspaceId   // воркспейс herdr, если этот worktree открыт
    ) {}

    /** Один воркспейс из `herdr workspace list --json` + его worktree'ы. */
    public record WorkspaceInfo(
            String id,            // workspace_id, напр. "wF"
            String label,
            Integer number,       // человекочитаемый порядковый номер
            String agentStatus,   // rollup-статус воркспейса, считает сам herdr
            boolean focused,
            int paneCount,
            int tabCount,
            List<WorktreeInfo> worktrees
    ) {}

    /**
     * Полное состояние одного хоста: конфигурация + последний известный снапшот
     * + health соединения. Именно это отдаётся наружу и хранится в Registry.
     */
    public record HostState(
            String id,                  // логический id из конфига (напр. "dqa2")
            String host,                // ssh-таргет
            Health health,
            Long lastUpdate,            // unix-время последнего успешного кадра
            List<WorkspaceInfo> workspaces,
            List<AgentInfo> agents
    ) {
        /** Начальное состояние до первого кадра. */
        public static HostState initial(String id, String host) {
            return new HostState(id, host, Health.UNREACHABLE, null, List.of(), List.of());
        }

        public HostState withHealth(Health h) {
            return new HostState(id, host, h, lastUpdate, workspaces, agents);
        }

        public HostState withFrame(Health h, Long ts,
                                   List<WorkspaceInfo> ws, List<AgentInfo> ag) {
            return new HostState(id, host, h, ts, ws, ag);
        }
    }

    /** Событие, рассылаемое в SSE. */
    public record StreamEvent(
            String type,        // "snapshot" | "host_update" | "host_remove" | "claude_usage" | "ping"
            Object data         // null для ping (heartbeat)
    ) {}
}

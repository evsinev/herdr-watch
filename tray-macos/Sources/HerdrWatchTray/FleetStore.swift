import AppKit

// In-memory fleet state (main-thread only). Applies SSE deltas and derives what the
// menu bar needs: the icon tint, a tooltip summary, and the sorted agent rows.
final class FleetStore {
    private(set) var hosts: [String: HostState] = [:]
    var connected = false
    /// Квота аккаунта Claude — не про хосты, поэтому живёт рядом, а не внутри HostState.
    var usage: ClaudeUsage?

    func applySnapshot(_ list: [HostState]) {
        // Full replace — this is the connect/reconnect baseline, so it emits no transitions.
        hosts = Dictionary(list.map { ($0.id, $0) }, uniquingKeysWith: { _, last in last })
    }
    func applyRemove(_ id: String) { hosts.removeValue(forKey: id) }

    // MARK: - Edge detection (status transitions), mirrors backend TelegramNotifier

    struct Transition {
        let host: String
        let project: String
        let from: String?
        let to: String
    }

    private static func agentKey(_ a: Agent) -> String {
        if let pane = a.paneId, !pane.isEmpty { return pane }
        return (a.workspaceId ?? "") + "/" + (a.title ?? "")
    }

    /// Project name for an agent = its workspace label (or workspace id) — NOT the
    /// worktree / terminal title. Falls back to the title only if no workspace matches.
    static func projectLabel(_ a: Agent, in host: HostState) -> String {
        if let wid = a.workspaceId,
           let ws = (host.workspaces ?? []).first(where: { $0.id == wid }) {
            if let label = ws.label, !label.trimmingCharacters(in: .whitespaces).isEmpty { return label }
            if let id = ws.id, !id.isEmpty { return id }
        }
        return a.title ?? a.paneId ?? "—"
    }

    /// Applies a single-host update and returns the agent status transitions vs the host's
    /// previous state. Returns [] when the host is new or was UNREACHABLE (silent baseline).
    func applyUpdate(_ host: HostState) -> [Transition] {
        let prev = hosts[host.id]
        hosts[host.id] = host
        guard let prev = prev, (prev.health ?? "").uppercased() != "UNREACHABLE" else { return [] }

        var prevStatus: [String: String] = [:]
        for a in prev.agents ?? [] { prevStatus[Self.agentKey(a)] = a.status ?? "unknown" }

        var out: [Transition] = []
        for a in host.agents ?? [] {
            guard let old = prevStatus[Self.agentKey(a)] else { continue }  // new agent → not a transition
            let to = a.status ?? "unknown"
            if old != to {
                out.append(Transition(host: host.id, project: Self.projectLabel(a, in: host), from: old, to: to))
            }
        }
        return out
    }

    struct Counts { var blocked = 0; var done = 0; var working = 0 }

    func counts() -> Counts {
        var c = Counts()
        for host in hosts.values {
            for agent in host.agents ?? [] {
                switch (agent.status ?? "").lowercased() {
                case "blocked": c.blocked += 1
                case "done":    c.done += 1
                case "working": c.working += 1
                default: break
                }
            }
        }
        return c
    }

    /// Level-based attention tint: red if anything is blocked, else blue if anything is
    /// done, else nil (neutral / theme template icon).
    func attentionTint() -> NSColor? {
        let c = counts()
        if c.blocked > 0 { return AgentStatus.color("blocked") }
        if c.done > 0 { return AgentStatus.color("done") }
        return nil
    }

    // MARK: - Claude quota

    var usageIsStale: Bool { usage?.state == "STALE" }

    /// Окна квоты в порядке отображения: `5h`, `7d`, затем помодельные (fable/opus/…).
    ///
    /// Отсутствующее окно ОПУСКАЕТСЯ, а не рисуется нулём: пустая полоса неотличима от
    /// 0%, а «не отчитывалось» и «ничего не потратили» — разные вещи (тот же инвариант,
    /// что и у бэкенда с `null`-окнами).
    ///
    /// Помодельные окна отдаёт только аккаунт-API (`claude-usage.source: pull|auto`), так
    /// что под дефолтным push полос будет две. Они же — разбивка недельного окна, а не
    /// третье окно, которое тебя останавливает: без 5h/7d сами по себе не показываются.
    /// `maxModels` ограничивает их число в меню-баре — место там не бесконечное.
    func usageGauges(maxModels: Int = .max) -> [UsageGauge] {
        guard let usage = usage, usage.state != "NOT_CONFIGURED" else { return [] }
        var out: [UsageGauge] = []
        if let w = usage.windows?.fiveHour {
            out.append(UsageGauge(label: "5h", percent: w.usedPercent, resetsAt: w.resetsAt))
        }
        if let w = usage.windows?.sevenDay {
            out.append(UsageGauge(label: "7d", percent: w.usedPercent, resetsAt: w.resetsAt))
        }
        guard !out.isEmpty else { return [] }
        for m in (usage.models ?? []).prefix(max(0, maxModels)) {
            out.append(UsageGauge(label: m.model.lowercased(), percent: m.usedPercent, resetsAt: nil))
        }
        return out
    }

    struct Row {
        let host: String
        let project: String
        let status: String
        let color: NSColor
        let dim: Bool
        let prio: Int
    }

    /// One row per agent, sorted like the Compact view: priority desc, host asc, project asc.
    func rows() -> [Row] {
        var out: [Row] = []
        for host in hosts.values {
            let dim = (host.health ?? "").uppercased() == "UNREACHABLE"
            for agent in host.agents ?? [] {
                let status = agent.status ?? "unknown"
                out.append(Row(
                    host: host.id,
                    project: Self.projectLabel(agent, in: host),
                    status: status,
                    color: AgentStatus.color(status),
                    dim: dim,
                    prio: AgentStatus.prio(status)))
            }
        }
        out.sort { lhs, rhs in
            if lhs.prio != rhs.prio { return lhs.prio > rhs.prio }
            if lhs.host != rhs.host { return lhs.host < rhs.host }
            return lhs.project < rhs.project
        }
        return out
    }
}

import AppKit

// In-memory fleet state (main-thread only). Applies SSE deltas and derives what the
// menu bar needs: the icon tint, a tooltip summary, and the sorted agent rows.
final class FleetStore {
    private(set) var hosts: [String: HostState] = [:]
    var connected = false

    func applySnapshot(_ list: [HostState]) {
        hosts = Dictionary(list.map { ($0.id, $0) }, uniquingKeysWith: { _, last in last })
    }
    func applyUpdate(_ host: HostState) { hosts[host.id] = host }
    func applyRemove(_ id: String) { hosts.removeValue(forKey: id) }

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

    struct Row {
        let host: String
        let title: String
        let status: String
        let color: NSColor
        let dim: Bool
        let prio: Int
    }

    /// One row per agent, sorted like the Compact view: priority desc, host asc, title asc.
    func rows() -> [Row] {
        var out: [Row] = []
        for host in hosts.values {
            let dim = (host.health ?? "").uppercased() == "UNREACHABLE"
            for agent in host.agents ?? [] {
                let status = agent.status ?? "unknown"
                let title = agent.title ?? agent.paneId ?? "—"
                out.append(Row(
                    host: host.id,
                    title: title,
                    status: status,
                    color: AgentStatus.color(status),
                    dim: dim,
                    prio: AgentStatus.prio(status)))
            }
        }
        out.sort { lhs, rhs in
            if lhs.prio != rhs.prio { return lhs.prio > rhs.prio }
            if lhs.host != rhs.host { return lhs.host < rhs.host }
            return lhs.title < rhs.title
        }
        return out
    }
}

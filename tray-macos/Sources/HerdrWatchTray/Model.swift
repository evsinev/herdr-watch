import Foundation

// Mirrors the server's SSE payload (backend com.payneteasy.herdrwatch.model.Model).
// Fields are camelCase to match Jackson's default output. Most are optional to stay
// robust against nulls / future fields.

struct Worktree: Decodable {
    var branch: String?
    var path: String?
    var label: String?
    var detached: Bool?
    var prunable: Bool?
    var linked: Bool?
    var openWorkspaceId: String?
}

struct Workspace: Decodable {
    var id: String?
    var label: String?
    var number: Int?
    var agentStatus: String?
    var focused: Bool?
    var paneCount: Int?
    var tabCount: Int?
    var worktrees: [Worktree]?
}

struct Agent: Decodable {
    var title: String?
    var kind: String?
    var status: String?          // idle | working | blocked | done | unknown
    var workspaceId: String?
    var tabId: String?
    var paneId: String?
    var focused: Bool?
    var cwd: String?
}

struct HostState: Decodable {
    var id: String
    var host: String?
    var health: String?          // CONNECTED | DEGRADED | UNREACHABLE
    var lastUpdate: Int64?
    var workspaces: [Workspace]?
    var agents: [Agent]?
}

// SSE envelope: { "type": ..., "data": ... }. `data` shape depends on `type`.
struct StreamEvent: Decodable {
    enum Payload {
        case snapshot([HostState])   // whole fleet
        case update(HostState)       // one host
        case remove(String)          // host id
        case ping                    // keepalive — no data, ignored
    }
    let payload: Payload

    private enum CodingKeys: String, CodingKey { case type, data }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        let type = try c.decode(String.self, forKey: .type)
        switch type {
        case "snapshot":
            payload = .snapshot(try c.decode([HostState].self, forKey: .data))
        case "host_update":
            payload = .update(try c.decode(HostState.self, forKey: .data))
        case "host_remove":
            let holder = try c.decode([String: String].self, forKey: .data)
            payload = .remove(holder["id"] ?? "")
        case "ping":
            // Heartbeat from the server (data is null). MUST decode successfully so the
            // eager-decode buffer in SSEClient clears; a thrown error would poison it.
            payload = .ping
        default:
            throw DecodingError.dataCorruptedError(
                forKey: .type, in: c, debugDescription: "unknown stream event type: \(type)")
        }
    }
}

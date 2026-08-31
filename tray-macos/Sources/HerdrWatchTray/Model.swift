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

// Квота подписки Claude — зеркало backend `usage/ClaudeUsage.java` (SSE `claude_usage`,
// `GET /api/claude-usage`). Отсутствующее окно приходит как null и НИКОГДА не как 0%:
// «не отчитывалось» и «ничего не потратили» — разные вещи. `source`/`models` опциональны
// намеренно — сервер постарше их не отдаёт, и это не повод уронить декодирование.

struct UsageWindow: Decodable {
    var usedPercent: Int
    var resetsAt: Double         // unix-время сброса окна
}

struct UsageWindows: Decodable {
    var fiveHour: UsageWindow?
    var sevenDay: UsageWindow?
}

struct UsageModelWindow: Decodable {
    var model: String            // имя модели как его прислал сервер (набор ОТКРЫТ)
    var usedPercent: Int
    var resetsAt: Double
}

struct ClaudeUsage: Decodable {
    var state: String            // NOT_CONFIGURED | OK | STALE
    var source: String?          // NONE | STATUSLINE | ACCOUNT_API
    var capturedAt: Double?      // unix-время снятия показаний
    var error: String?
    var windows: UsageWindows?
    var models: [UsageModelWindow]?
}

// SSE envelope: { "type": ..., "data": ... }. `data` shape depends on `type`.
struct StreamEvent: Decodable {
    enum Payload {
        case snapshot([HostState])   // whole fleet
        case update(HostState)       // one host
        case remove(String)          // host id
        case usage(ClaudeUsage)      // Claude subscription quota (whole account)
        case ping                    // keepalive — no data, ignored
        case unknown(String)         // event type this build doesn't know
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
        case "claude_usage":
            payload = .usage(try c.decode(ClaudeUsage.self, forKey: .data))
        case "ping":
            // Heartbeat from the server (data is null). MUST decode successfully so the
            // eager-decode buffer in SSEClient clears; a thrown error would poison it.
            payload = .ping
        default:
            // Тоже ОБЯЗАНО декодироваться: на брошенной ошибке eager-буфер в SSEClient
            // не очищается, следующая строка дописывается к нему — и поток отравлен
            // навсегда. Незнакомое событие игнорируем, а не роняем стрим.
            payload = .unknown(type)
        }
    }
}

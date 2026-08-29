// JSON-контракт бэкенда (camelCase). Схемы herdr выверены в спецификации.

export type Health = "CONNECTED" | "DEGRADED" | "UNREACHABLE";

export interface WorktreeInfo {
  branch: string | null;
  path: string | null;
  label: string | null;
  detached: boolean;
  prunable: boolean;
  linked: boolean;
  openWorkspaceId: string | null;
}

export interface AgentInfo {
  title: string | null; // terminal_title_stripped
  kind: string | null; // herdr "agent": claude / codex / pi / ...
  status: string | null; // agent_status: idle|working|blocked|done|unknown
  workspaceId: string | null;
  tabId: string | null;
  paneId: string | null;
  focused: boolean;
  cwd: string | null;
}

export interface WorkspaceInfo {
  id: string; // workspace_id, напр. "wF"
  label: string | null;
  number: number | null; // человекочитаемый порядковый
  agentStatus: string | null; // rollup от herdr — используем как есть
  focused: boolean;
  paneCount: number;
  tabCount: number;
  worktrees: WorktreeInfo[];
}

export interface HostState {
  id: string;
  host: string;
  health: Health;
  lastUpdate: number | null; // unix seconds
  workspaces: WorkspaceInfo[];
  agents: AgentInfo[];
}

// Квота подписки Claude (аккаунт целиком, не хост). Показания приносит
// statusline-хук Claude Code; окно, о котором показаний нет, приходит как null —
// именно null, а не 0%: «не отчиталось» и «израсходовано 0%» — разные вещи.
export type ClaudeUsageState = "NOT_CONFIGURED" | "OK" | "STALE";

export interface ClaudeUsageWindow {
  usedPercent: number; // целые проценты 0..100
  resetsAt: number; // unix seconds
}

/**
 * Кто наблюдал показания. Набор ОТКРЫТ: бэкенд волен завести третий источник, и
 * незнакомое имя обязано доехать до экрана как есть, а не превратиться в «нет данных».
 */
export type ClaudeUsageSource = "NONE" | "STATUSLINE" | "ACCOUNT_API" | (string & {});

/** Недельное окно, привязанное к модели. Имя модели — как прислал сервер. */
export interface ClaudeUsageModelWindow {
  model: string;
  usedPercent: number; // целые проценты 0..100
  resetsAt: number; // unix seconds
}

export interface ClaudeUsage {
  state: ClaudeUsageState;
  source: ClaudeUsageSource; // никогда не null
  capturedAt: number | null; // unix seconds; null — показаний не было ни разу
  error: string | null; // причина деградации
  windows: {
    fiveHour: ClaudeUsageWindow | null;
    sevenDay: ClaudeUsageWindow | null;
  };
  models: ClaudeUsageModelWindow[]; // пусто, если помодельных окон не отдали
}

// SSE-события (безымянные message-события {type, data}).
export type StreamEvent =
  | { type: "snapshot"; data: HostState[] }
  | { type: "host_update"; data: HostState }
  | { type: "host_remove"; data: { id: string } }
  | { type: "claude_usage"; data: ClaudeUsage };

// Settings / CRUD.
export type DataSourceMode = "command" | "socket"; // CLI herdr vs прямой unix-сокет

export interface ServerView {
  id: string;
  host: string;
  herdrPath: string;
  pollInterval: number;
  reconnectDelay: number;
  enabled: boolean;
  sshExtraOpts: string | null;
  local: boolean;
  dataSource: DataSourceMode;
  socketPath: string | null;
  health: Health;
  lastUpdate: number | null;
}

export interface HostRequest {
  id: string;
  host: string;
  herdrPath?: string;
  pollInterval: number;
  reconnectDelay: number;
  enabled: boolean;
  sshExtraOpts?: string | null;
  local: boolean;
  dataSource?: DataSourceMode;
  socketPath?: string | null;
}

export interface ApiErrors {
  errors: Record<string, string>;
}

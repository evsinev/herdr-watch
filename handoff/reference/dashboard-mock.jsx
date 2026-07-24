import React, { useState, useEffect, useRef, useMemo } from "react";

/*
 * herdr-watch dashboard
 *
 * Один EventSource("/api/stream") -> событие "snapshot" (полное состояние),
 * далее "host_update" (дельты). Иерархия ровно как в herdr:
 *   host (health) -> workspaces -> agents (agent_status).
 *
 * В этом превью SSE-эндпоинта нет, поэтому есть USE_MOCK: генерим синтетические
 * данные с той же формой, что отдаёт бэкенд. В реальном Vite-проекте поставь
 * USE_MOCK = false и подключи shadcn Card / Badge вместо локальных примитивов.
 */

const USE_MOCK = true;

// --- семантика статусов агента (та же, что в bash-скрипте: blocked>working>done>idle)
const AGENT_STATUS = {
  blocked: { label: "blocked", dot: "#E24B4A", fg: "#A32D2D", bg: "#FCEBEB" },
  working: { label: "working", dot: "#EF9F27", fg: "#854F0B", bg: "#FAEEDA" },
  done:    { label: "done",    dot: "#378ADD", fg: "#0C447C", bg: "#E6F1FB" },
  idle:    { label: "idle",    dot: "#639922", fg: "#3B6D11", bg: "#EAF3DE" },
  unknown: { label: "unknown", dot: "#888780", fg: "#444441", bg: "#F1EFE8" },
};

const HEALTH = {
  connected:   { label: "connected",   fg: "#3B6D11", bg: "#EAF3DE", dot: "#639922" },
  degraded:    { label: "degraded",    fg: "#854F0B", bg: "#FAEEDA", dot: "#EF9F27" },
  unreachable: { label: "unreachable", fg: "#5F5E5A", bg: "#F1EFE8", dot: "#B4B2A9" },
};

// rollup хоста: самый срочный статус среди воркспейсов (herdr сам считает их agent_status)
function hostRollup(workspaces, agents) {
  const order = ["blocked", "working", "done", "idle", "unknown"];
  const statuses = workspaces.length
    ? workspaces.map((w) => w.agentStatus)
    : agents.map((a) => a.status);
  for (const s of order) if (statuses.some((x) => x === s)) return s;
  return "idle";
}

function StatusDot({ color }) {
  return (
    <span
      style={{
        width: 8, height: 8, borderRadius: "50%",
        background: color, display: "inline-block", flexShrink: 0,
      }}
    />
  );
}

function Badge({ palette, children }) {
  return (
    <span
      style={{
        display: "inline-flex", alignItems: "center", gap: 6,
        padding: "2px 8px", borderRadius: 6, fontSize: 12,
        fontWeight: 500, color: palette.fg, background: palette.bg,
        fontFamily: "ui-monospace, SFMono-Regular, Menlo, monospace",
      }}
    >
      {palette.dot && <StatusDot color={palette.dot} />}
      {children}
    </span>
  );
}

function AgentRow({ agent }) {
  const st = AGENT_STATUS[agent.status] || AGENT_STATUS.unknown;
  return (
    <div
      style={{
        display: "flex", alignItems: "center", justifyContent: "space-between",
        padding: "6px 10px", borderRadius: 6,
        background: agent.focused ? "rgba(120,120,120,0.06)" : "transparent",
      }}
    >
      <div style={{ display: "flex", alignItems: "center", gap: 8, minWidth: 0 }}>
        <StatusDot color={st.dot} />
        <span style={{ fontFamily: "ui-monospace, Menlo, monospace", fontSize: 13, fontWeight: 500, whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis" }}>
          {agent.title}
        </span>
        {agent.kind && (
          <span style={{ fontSize: 11, color: "#888780", fontFamily: "ui-monospace, Menlo, monospace" }}>
            {agent.kind}
          </span>
        )}
        <span style={{ fontSize: 11, color: "#B4B2A9", fontFamily: "ui-monospace, Menlo, monospace" }}>
          {agent.paneId}
        </span>
      </div>
      <Badge palette={st}>{st.label}</Badge>
    </div>
  );
}

function WorktreeRow({ wt }) {
  // визуальные флаги: detached / prunable — потенциальные проблемы
  const flag = wt.detached ? "detached" : wt.prunable ? "prunable" : null;
  const flagColor = wt.detached ? "#A32D2D" : "#854F0B";
  return (
    <div style={{ display: "flex", alignItems: "center", gap: 8, padding: "3px 10px 3px 26px", fontSize: 12 }}>
      <span style={{ color: "#888780", fontFamily: "ui-monospace, Menlo, monospace", flexShrink: 0 }}>⌥</span>
      <span style={{ fontFamily: "ui-monospace, Menlo, monospace", color: "#5F5E5A", whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis" }}>
        {wt.branch}
      </span>
      {wt.openWorkspaceId && (
        <span style={{ fontSize: 10, color: "#B4B2A9", fontFamily: "ui-monospace, Menlo, monospace" }}>
          open · {wt.openWorkspaceId}
        </span>
      )}
      {flag && (
        <span style={{ fontSize: 10, color: flagColor, fontFamily: "ui-monospace, Menlo, monospace" }}>
          {flag}
        </span>
      )}
    </div>
  );
}

function WorkspaceGroup({ ws, agents }) {
  const wsAgents = agents.filter((a) => a.workspaceId === ws.id);
  const wst = ws.agentStatus ? AGENT_STATUS[ws.agentStatus] : null;
  const worktrees = ws.worktrees || [];
  return (
    <div style={{ marginTop: 10 }}>
      <div style={{ display: "flex", alignItems: "center", gap: 8, marginBottom: 4 }}>
        {ws.number != null && (
          <span style={{ fontSize: 11, color: "#B4B2A9", fontFamily: "ui-monospace, Menlo, monospace" }}>
            {ws.number}
          </span>
        )}
        {wst && <StatusDot color={wst.dot} />}
        <span style={{ fontSize: 11, textTransform: "uppercase", letterSpacing: "0.06em", color: ws.focused ? "#444441" : "#888780", fontWeight: 500 }}>
          {ws.label || ws.id}
        </span>
        <span style={{ fontSize: 11, color: "#B4B2A9", fontFamily: "ui-monospace, Menlo, monospace" }}>
          {ws.id}
        </span>
        {ws.focused && (
          <span style={{ fontSize: 10, color: "#B4B2A9" }}>focused</span>
        )}
      </div>
      {wsAgents.length === 0 ? (
        <div style={{ fontSize: 12, color: "#B4B2A9", padding: "4px 10px" }}>нет детектированных агентов</div>
      ) : (
        wsAgents.map((a) => <AgentRow key={a.paneId} agent={a} />)
      )}
      {worktrees.length > 0 && worktrees.map((wt) => (
        <WorktreeRow key={wt.path || wt.branch} wt={wt} />
      ))}
    </div>
  );
}

function HostCard({ host }) {
  const health = HEALTH[host.health?.toLowerCase()] || HEALTH.unreachable;
  const dim = host.health?.toLowerCase() === "unreachable";
  const rollup = AGENT_STATUS[hostRollup(host.workspaces, host.agents)] || AGENT_STATUS.idle;

  // агенты без соответствующего воркспейса в списке
  const orphanAgents = host.agents.filter(
    (a) => !host.workspaces.some((w) => w.id === a.workspaceId)
  );

  const ago = host.lastUpdate
    ? `${Math.max(0, Math.floor(Date.now() / 1000 - host.lastUpdate))}s ago`
    : "—";

  return (
    <div
      style={{
        border: "1px solid #E5E3DC", borderRadius: 12, padding: 16,
        background: "#fff", opacity: dim ? 0.6 : 1,
        transition: "opacity 0.3s",
      }}
    >
      <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between" }}>
        <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
          <StatusDot color={rollup.dot} />
          <span style={{ fontSize: 15, fontWeight: 500, fontFamily: "ui-monospace, Menlo, monospace" }}>
            {host.id}
          </span>
          <span style={{ fontSize: 12, color: "#B4B2A9", fontFamily: "ui-monospace, Menlo, monospace" }}>
            {host.host}
          </span>
        </div>
        <Badge palette={health}>{health.label}</Badge>
      </div>

      <div style={{ display: "flex", gap: 12, marginTop: 6, fontSize: 11, color: "#888780" }}>
        <span>{host.agents.length} agents</span>
        <span>{host.workspaces.length} workspaces</span>
        <span>updated {ago}</span>
      </div>

      {host.workspaces.map((ws) => (
        <WorkspaceGroup key={ws.id} ws={ws} agents={host.agents} />
      ))}

      {orphanAgents.length > 0 && (
        <WorkspaceGroup
          ws={{ id: "—", label: "прочие панели" }}
          agents={orphanAgents.map((a) => ({ ...a, workspaceId: "—" }))}
        />
      )}
    </div>
  );
}

// ---- mock-генератор: имитирует поток кадров бэкенда
function useMockHosts() {
  const [hosts, setHosts] = useState(() => seedHosts());
  useEffect(() => {
    const t = setInterval(() => {
      setHosts((prev) => {
        const next = new Map(prev);
        for (const [id, h] of next) {
          if (h.health === "UNREACHABLE") continue;
          const agents = h.agents.map((a) =>
            Math.random() < 0.25 ? { ...a, status: randomStatus() } : a
          );
          // статус воркспейса = самый срочный из его агентов (как считает herdr)
          const workspaces = h.workspaces.map((w) => {
            const st = agents.filter((a) => a.workspaceId === w.id).map((a) => a.status);
            const order = ["blocked", "working", "done", "idle", "unknown"];
            const roll = order.find((s) => st.includes(s)) || w.agentStatus;
            return { ...w, agentStatus: roll };
          });
          next.set(id, { ...h, agents, workspaces, lastUpdate: Math.floor(Date.now() / 1000) });
        }
        return next;
      });
    }, 2000);
    return () => clearInterval(t);
  }, []);
  return hosts;
}

function randomStatus() {
  const s = ["idle", "working", "blocked", "done"];
  return s[Math.floor(Math.random() * s.length)];
}

function seedHosts() {
  const wt = (branch, path, openWorkspaceId = null, detached = false, prunable = false) =>
    ({ branch, path, label: "", detached, prunable, linked: false, openWorkspaceId });
  const ws = (id, label, number, agentStatus, focused = false, worktrees = []) =>
    ({ id, label, number, agentStatus, focused, paneCount: 1, tabCount: 1, worktrees });
  const ag = (title, kind, status, workspaceId, paneId, focused = false) =>
    ({ title, kind, status, workspaceId, tabId: `${workspaceId}:t1`, paneId, focused, cwd: "" });
  const mk = (id, host, health, workspaces, agents) =>
    [id, { id, host, health, lastUpdate: Math.floor(Date.now() / 1000), workspaces, agents }];

  return new Map([
    mk("m3-local", "m3-local", "CONNECTED",
      [
        ws("wF", "dc-agent", 3, "idle", false,
          [wt("main", "/Users/esinev/svn/dc-agent", "wF")]),
        ws("wG", "uman", 4, "idle", false,
          [wt("feature/gitlab-import", "/Users/esinev/svn/uman", "wG"),
           wt("main", "/Users/esinev/svn/uman-main", null, false, true)]),
        ws("wH", "deploy", 5, "working", true,
          [wt("diff-service-redmine-jdk21", "/Users/esinev/svn/deploy", "wH")]),
      ],
      [
        ag("enable-ossindex-sonatype-auth", "claude", "idle", "wF", "wF:p1"),
        ag("Claude Code", "claude", "idle", "wG", "wG:p1"),
        ag("ci-tests-approach-a-b", "claude", "working", "wH", "wH:p1", true),
      ]),
    mk("dqa1", "dqa1", "CONNECTED",
      [ws("wA", "paynet-ui-mcp", 1, "blocked", false,
          [wt("fix/approval-rate", "/srv/paynet-ui-mcp", "wA")])],
      [ag("fix-approval-rate-calc", "claude", "blocked", "wA", "wA:p1")]),
    mk("dqa2", "dqa2", "DEGRADED",
      [ws("wA", "ydb-user-admin", 1, "idle")],
      [ag("tenant-tls-setup", "pi", "idle", "wA", "wA:p1")]),
    mk("dqa3", "dqa3", "UNREACHABLE", [], []),
  ]);
}

// ---- реальный SSE-хук (используется при USE_MOCK=false)
function useSseHosts() {
  const [hosts, setHosts] = useState(new Map());
  const esRef = useRef(null);
  useEffect(() => {
    const es = new EventSource("/api/stream");
    esRef.current = es;
    es.addEventListener("snapshot", (e) => {
      const arr = JSON.parse(e.data);
      setHosts(new Map(arr.map((h) => [h.id, h])));
    });
    es.addEventListener("host_update", (e) => {
      const h = JSON.parse(e.data);
      setHosts((prev) => new Map(prev).set(h.id, h));
    });
    return () => es.close();
  }, []);
  return hosts;
}

export default function App() {
  const hosts = USE_MOCK ? useMockHosts() : useSseHosts();
  const list = useMemo(() => {
    const arr = [...hosts.values()];
    const rank = { blocked: 0, working: 1, done: 2, idle: 3, unknown: 4 };
    return arr.sort((a, b) => {
      // недоступные вниз, дальше по срочности rollup
      const au = a.health === "UNREACHABLE" ? 1 : 0;
      const bu = b.health === "UNREACHABLE" ? 1 : 0;
      if (au !== bu) return au - bu;
      return rank[hostRollup(a.workspaces, a.agents)] - rank[hostRollup(b.workspaces, b.agents)];
    });
  }, [hosts]);

  const totals = useMemo(() => {
    const all = list.flatMap((h) => h.agents);
    return {
      blocked: all.filter((a) => a.status === "blocked").length,
      working: all.filter((a) => a.status === "working").length,
      hosts: list.length,
      down: list.filter((h) => h.health === "UNREACHABLE").length,
    };
  }, [list]);

  return (
    <div style={{ minHeight: "100vh", background: "#FAF9F5", padding: "24px 20px", fontFamily: "system-ui, -apple-system, sans-serif" }}>
      <div style={{ maxWidth: 720, margin: "0 auto" }}>
        <div style={{ display: "flex", alignItems: "baseline", justifyContent: "space-between", marginBottom: 4 }}>
          <h1 style={{ fontSize: 20, fontWeight: 500, margin: 0, fontFamily: "ui-monospace, Menlo, monospace" }}>
            herdr-watch
          </h1>
          <div style={{ display: "flex", gap: 12, fontSize: 12, color: "#888780", fontFamily: "ui-monospace, Menlo, monospace" }}>
            <span>{totals.hosts} hosts</span>
            {totals.down > 0 && <span style={{ color: "#A32D2D" }}>{totals.down} down</span>}
            {totals.blocked > 0 && <span style={{ color: "#A32D2D" }}>{totals.blocked} blocked</span>}
            {totals.working > 0 && <span style={{ color: "#854F0B" }}>{totals.working} working</span>}
          </div>
        </div>
        <p style={{ fontSize: 12, color: "#B4B2A9", margin: "0 0 20px", fontFamily: "ui-monospace, Menlo, monospace" }}>
          {USE_MOCK ? "mock stream · подключи /api/stream в проде" : "live · /api/stream"}
        </p>

        <div style={{ display: "flex", flexDirection: "column", gap: 12 }}>
          {list.map((h) => (
            <HostCard key={h.id} host={h} />
          ))}
        </div>
      </div>
    </div>
  );
}

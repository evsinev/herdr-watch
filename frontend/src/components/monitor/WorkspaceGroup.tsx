import type { AgentInfo, WorkspaceInfo } from "@/lib/types";
import { statusOf } from "@/lib/theme";
import { AgentRow } from "./AgentRow";
import { WorktreeRow } from "./WorktreeRow";

export function WorkspaceGroup({
  ws,
  agents,
}: {
  ws: WorkspaceInfo;
  agents: AgentInfo[];
}) {
  // точка воркспейса — rollup-статус, который считает сам herdr
  const dot = statusOf(ws.agentStatus).color;
  const name = (ws.label ?? ws.id).toUpperCase();

  return (
    <div className="border-b border-white/[0.05] last:border-b-0">
      {/* заголовок воркспейса */}
      <div
        className="flex items-center gap-2 px-4 py-1.5"
        style={{ background: ws.focused ? "rgba(255,255,255,0.025)" : "transparent" }}
      >
        {ws.number != null && (
          <span className="font-mono text-[11px] text-muted-2">{ws.number}</span>
        )}
        <span
          className="inline-block h-[7px] w-[7px] rounded-full"
          style={{ background: dot }}
        />
        <span className="font-sans text-[11px] font-semibold uppercase tracking-[0.08em] text-ink-2">
          {name}
        </span>
        <span className="font-mono text-[11px] text-muted-2">{ws.id}</span>
        {ws.focused && (
          <span
            className="rounded-[4px] px-1.5 py-px font-mono text-[10px] text-focus"
            style={{ border: "1px solid rgba(208,162,74,0.4)" }}
          >
            focused
          </span>
        )}
      </div>

      {/* агенты воркспейса */}
      {agents.map((a) => (
        <AgentRow key={a.paneId ?? `${ws.id}:${a.title}`} agent={a} />
      ))}

      {/* вложенные worktree'ы */}
      {ws.worktrees.map((wt, i) => (
        <WorktreeRow key={wt.path ?? wt.branch ?? `wt${i}`} wt={wt} />
      ))}
    </div>
  );
}

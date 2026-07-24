import type { AgentInfo } from "@/lib/types";
import { badgeStyle, hex, statusOf } from "@/lib/theme";

export function AgentRow({ agent }: { agent: AgentInfo }) {
  const s = statusOf(agent.status);
  const label = (agent.status ?? "unknown").toLowerCase();

  return (
    <div
      className="flex items-center gap-2 py-1.5 pl-[30px] pr-4"
      style={{ background: agent.focused ? hex(s.color, 0.07) : "transparent" }}
    >
      <span
        className="inline-block h-2 w-2 shrink-0 rounded-full"
        style={{ background: s.color, boxShadow: `0 0 0 3px ${hex(s.color, 0.12)}` }}
      />
      <span
        className="min-w-0 flex-1 truncate font-mono text-[12.5px]"
        style={{ color: "#d6d9df" }}
        title={agent.title ?? undefined}
      >
        {agent.title ?? agent.paneId ?? "—"}
      </span>
      {agent.kind && (
        <span className="shrink-0 font-mono text-[11px] text-muted">{agent.kind}</span>
      )}
      {agent.paneId && (
        <span className="shrink-0 font-mono text-[10.5px] text-muted-3">{agent.paneId}</span>
      )}
      <span
        className="shrink-0 rounded-[4px] px-2 py-0.5 font-mono text-[10.5px] font-medium uppercase tracking-[0.04em]"
        style={badgeStyle(s.color)}
      >
        {label}
      </span>
    </div>
  );
}

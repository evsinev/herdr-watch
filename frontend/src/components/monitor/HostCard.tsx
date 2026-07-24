import type { HostState } from "@/lib/types";
import { badgeStyle, healthOf } from "@/lib/theme";
import { WorkspaceGroup } from "./WorkspaceGroup";

function agoSeconds(lastUpdate: number | null): number | null {
  if (!lastUpdate) return null;
  return Math.max(0, Math.round(Date.now() / 1000 - lastUpdate));
}

export function HostCard({ host }: { host: HostState }) {
  const unreachable = host.health === "UNREACHABLE";
  const h = healthOf(host.health);
  const age = agoSeconds(host.lastUpdate);

  const workspaces = [...host.workspaces].sort(
    (a, b) => (a.number ?? 9999) - (b.number ?? 9999),
  );
  const empty = workspaces.length === 0;

  const meta = [
    `${host.agents.length} agents`,
    `${host.workspaces.length} workspaces`,
    age != null ? `updated ${age}s ago` : "no data yet",
  ].join(" · ");

  return (
    <div
      className="overflow-hidden rounded-lg border border-line bg-card"
      style={{ opacity: unreachable ? 0.6 : 1 }}
    >
      {/* header */}
      <div className="border-b border-white/[0.06] px-4 py-3.5">
        <div className="flex items-center gap-2">
          <span className="font-mono text-[14px] font-bold text-ink">{host.id}</span>
          <span className="font-mono text-[12px] text-muted-2">{host.host}</span>
          <span className="ml-auto flex items-center gap-1.5 rounded-[5px] px-2 py-0.5"
            style={badgeStyle(h.color)}
          >
            <span
              className="inline-block h-[7px] w-[7px] rounded-full"
              style={{ background: h.color }}
            />
            <span className="font-mono text-[11px]">{h.label}</span>
          </span>
        </div>
        <div className="mt-1.5 font-mono text-[11.5px] text-muted">{meta}</div>
      </div>

      {/* body */}
      {empty ? (
        <div className="px-4 py-6 text-center font-mono text-[12px] text-muted-3">
          — no workspaces · last contact lost —
        </div>
      ) : (
        workspaces.map((ws) => (
          <WorkspaceGroup
            key={ws.id}
            ws={ws}
            agents={host.agents.filter((a) => a.workspaceId === ws.id)}
          />
        ))
      )}
    </div>
  );
}

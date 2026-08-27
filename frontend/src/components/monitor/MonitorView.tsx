import type { ClaudeUsage, HostState } from "@/lib/types";
import { sortHosts } from "@/lib/sort";
import { HostCard } from "./HostCard";

export function MonitorView({
  hosts,
  usage,
  localHosts,
}: {
  hosts: Map<string, HostState>;
  usage: ClaudeUsage | null;
  localHosts: Set<string>;
}) {
  const sorted = sortHosts([...hosts.values()]);

  return (
    <div
      className="mx-auto grid max-w-grid items-start gap-4 px-7 pb-16 pt-6"
      style={{ gridTemplateColumns: "repeat(auto-fill, minmax(380px, 1fr))" }}
    >
      {sorted.length === 0 ? (
        <div className="col-span-full py-24 text-center font-mono text-[13px] text-muted-2">
          — no hosts · waiting for stream —
        </div>
      ) : (
        sorted.map((h) => (
          <HostCard key={h.id} host={h} usage={localHosts.has(h.id) ? usage : null} />
        ))
      )}
    </div>
  );
}

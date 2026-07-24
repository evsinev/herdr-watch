import type { HostState } from "@/lib/types";

interface Chip {
  key: string;
  value: number;
  label: string;
  color: string;
}

/**
 * Правая часть шапки: всего хостов + чипы (только при >0) в порядке
 * down (unreachable-хосты) · blocked (агенты) · working (агенты).
 */
export function SummaryBar({ hosts }: { hosts: HostState[] }) {
  let down = 0;
  let blocked = 0;
  let working = 0;
  for (const h of hosts) {
    if (h.health === "UNREACHABLE") down++;
    for (const a of h.agents) {
      if (a.status === "blocked") blocked++;
      else if (a.status === "working") working++;
    }
  }

  const chips: Chip[] = [
    { key: "down", value: down, label: "down", color: "#888780" },
    { key: "blocked", value: blocked, label: "blocked", color: "#E24B4A" },
    { key: "working", value: working, label: "working", color: "#EF9F27" },
  ].filter((c) => c.value > 0);

  return (
    <div className="flex items-center gap-4 font-mono text-[12.5px]">
      <span className="text-muted">
        <span className="text-ink-2">{hosts.length}</span> hosts
      </span>
      {chips.map((c) => (
        <span key={c.key} className="flex items-center gap-1.5">
          <span
            className="inline-block h-[7px] w-[7px] rounded-full"
            style={{ background: c.color }}
          />
          <span style={{ color: c.color }}>{c.value}</span>
          <span className="text-muted">{c.label}</span>
        </span>
      ))}
    </div>
  );
}

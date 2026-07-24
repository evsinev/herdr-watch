import type { HostState } from "@/lib/types";
import type { View } from "@/App";
import { SummaryBar } from "./SummaryBar";
import { cn } from "@/lib/utils";
import { hex } from "@/lib/theme";

interface Props {
  view: View;
  onView: (v: View) => void;
  hosts: Map<string, HostState>;
  offline: boolean;
}

const TABS: { key: View; label: string }[] = [
  { key: "monitor", label: "Monitor" },
  { key: "compact", label: "Compact" },
  { key: "settings", label: "Settings" },
];

export function Header({ view, onView, hosts, offline }: Props) {
  const list = [...hosts.values()];
  const statusColor = offline ? "#EF9F27" : "#639922";

  return (
    <header
      className="sticky top-0 z-40 border-b border-line backdrop-blur-md"
      style={{ background: "rgba(11,13,16,0.94)" }}
    >
      <div className="flex items-center gap-6 px-7 py-3">
        <div className="flex items-baseline gap-2.5">
          <span className="font-mono text-[15px] font-bold text-ink">herdr-watch</span>
          <span className="font-sans text-[11px] uppercase tracking-[0.12em] text-muted-2">
            fleet monitor
          </span>
        </div>

        <nav className="flex items-center gap-1">
          {TABS.map((t) => {
            const active = view === t.key;
            return (
              <button
                key={t.key}
                onClick={() => onView(t.key)}
                className={cn(
                  "border-b-2 px-2 pb-1 pt-1 font-sans text-[13px] transition-colors",
                  active
                    ? "border-accent text-ink"
                    : "border-transparent text-muted hover:text-ink-2",
                )}
              >
                {t.label}
              </button>
            );
          })}
        </nav>

        <div className="ml-auto flex items-center gap-4">
          <span
            className="flex items-center gap-1.5 font-mono text-[11px]"
            style={{ color: statusColor }}
            title={offline ? "backend disconnected" : "connected to backend"}
          >
            <span
              className="inline-block h-[7px] w-[7px] rounded-full"
              style={{ background: statusColor }}
            />
            {offline ? "offline" : "live"}
          </span>
          <SummaryBar hosts={list} />
        </div>
      </div>

      {offline && (
        <div
          className="px-7 py-1.5 text-center font-mono text-[12px]"
          style={{
            background: hex("#EF9F27", 0.12),
            borderTop: `1px solid ${hex("#EF9F27", 0.3)}`,
            color: "#EF9F27",
          }}
        >
          backend disconnected — reconnecting…
        </div>
      )}
    </header>
  );
}

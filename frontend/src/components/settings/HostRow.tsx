import { useState } from "react";
import type { ServerView } from "@/lib/types";
import { healthOf } from "@/lib/theme";
import { Switch } from "@/components/ui/switch";
import { Button } from "@/components/ui/button";

interface Props {
  server: ServerView;
  cols: string;
  onEdit: () => void;
  onRemove: () => void;
  onToggle: (enabled: boolean) => void;
}

export function HostRow({ server: s, cols, onEdit, onRemove, onToggle }: Props) {
  const [confirm, setConfirm] = useState(false);
  const h = healthOf(s.health);

  return (
    <div
      className="grid items-center gap-3 border-b border-white/[0.05] px-4 py-3 last:border-b-0"
      style={{ gridTemplateColumns: cols, opacity: s.enabled ? 1 : 0.55 }}
    >
      <span className="truncate font-mono text-[13px] text-ink">{s.id}</span>
      <span className="flex min-w-0 items-center gap-1.5">
        {s.local ? (
          <span className="shrink-0 rounded-[4px] border border-accent/40 bg-accent/10 px-1.5 py-0.5 font-mono text-[10.5px] text-accent">
            local
          </span>
        ) : (
          <span className="truncate font-mono text-[12px] text-ink-3">{s.host}</span>
        )}
        {s.dataSource === "socket" && (
          <span className="shrink-0 rounded-[4px] border border-line-strong bg-white/[0.04] px-1.5 py-0.5 font-mono text-[10.5px] text-muted">
            socket
          </span>
        )}
      </span>
      <span className="truncate font-mono text-[12px] text-muted">{s.herdrPath}</span>
      <span className="font-mono text-[12px] text-ink-3">{s.pollInterval}s</span>
      <span className="font-mono text-[12px] text-ink-3">{s.reconnectDelay}s</span>
      <span>
        <Switch checked={s.enabled} onCheckedChange={onToggle} aria-label="enabled" />
      </span>

      <div className="flex items-center justify-end gap-2">
        {confirm ? (
          <>
            <span className="font-mono text-[11.5px] text-danger">Remove {s.id}?</span>
            <Button
              size="sm"
              variant="danger"
              onClick={() => {
                setConfirm(false);
                onRemove();
              }}
            >
              Remove
            </Button>
            <Button size="sm" variant="outline" onClick={() => setConfirm(false)}>
              Cancel
            </Button>
          </>
        ) : (
          <>
            <span className="flex items-center gap-1.5">
              <span
                className="inline-block h-[7px] w-[7px] rounded-full"
                style={{ background: h.color }}
              />
              <span className="font-mono text-[11px]" style={{ color: h.color }}>
                {h.label}
              </span>
            </span>
            <Button size="sm" variant="outline" onClick={onEdit}>
              edit
            </Button>
            <Button
              size="sm"
              variant="ghost"
              className="text-[#9aa0aa]"
              onClick={() => setConfirm(true)}
            >
              remove
            </Button>
          </>
        )}
      </div>
    </div>
  );
}

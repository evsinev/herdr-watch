import type { WorktreeInfo } from "@/lib/types";
import { badgeStyle, FLAG } from "@/lib/theme";

export function WorktreeRow({ wt }: { wt: WorktreeInfo }) {
  const flag = wt.detached ? FLAG.detached : wt.prunable ? FLAG.prunable : null;

  return (
    <div className="flex items-center gap-2 py-[3px] pl-[44px] pr-4">
      <span className="shrink-0 font-mono text-[11px] text-muted-3">⌥</span>
      <span
        className="min-w-0 flex-1 truncate font-mono text-[11.5px] text-muted"
        title={wt.path ?? undefined}
      >
        {wt.branch ?? wt.path ?? "—"}
      </span>
      {wt.openWorkspaceId && (
        <span className="shrink-0 font-mono text-[10px] text-muted-3">
          open · {wt.openWorkspaceId}
        </span>
      )}
      {flag && (
        <span
          className="shrink-0 rounded-[3px] px-1.5 py-px font-mono text-[9.5px] uppercase"
          style={badgeStyle(flag.color, 0.3)}
        >
          {flag.label}
        </span>
      )}
    </div>
  );
}

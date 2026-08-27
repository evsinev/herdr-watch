import { useEffect, useRef, useState } from "react";
import { Maximize, Minimize } from "lucide-react";
import type { ClaudeUsage, HostState } from "@/lib/types";
import type { CompactLabel } from "@/lib/prefs";
import { compactCards } from "@/lib/sort";
import { hex } from "@/lib/theme";
import { cn } from "@/lib/utils";
import { useFullscreen } from "@/hooks/useFullscreen";
import { UsageTile, hasUsage } from "@/components/UsageGauge";

const GAP = 14;

/** Column count that makes N equal cells as large as possible in a w×h area. */
function bestColumns(n: number, w: number, h: number, gap: number): number {
  if (n <= 1) return 1;
  let best = 1;
  let bestScore = -1;
  for (let cols = 1; cols <= n; cols++) {
    const rows = Math.ceil(n / cols);
    const cellW = (w - gap * (cols - 1)) / cols;
    const cellH = (h - gap * (rows - 1)) / rows;
    if (cellW <= 0 || cellH <= 0) continue;
    const score = Math.min(cellW, cellH); // maximize the smaller side → big, ~square, equal cells
    if (score > bestScore) {
      bestScore = score;
      best = cols;
    }
  }
  return best;
}

const clamp = (min: number, v: number, max: number) => Math.max(min, Math.min(max, v));

/**
 * Compact — «плоский» экран для маленьких дисплеев (~7"): сетка одинаковых
 * карточек, по одной на агента. Статус передаётся ТОЛЬКО цветом. В фуллскрине
 * (реальном или `forceFull` из /compact/full) сетка растягивается на весь экран.
 * На /compact/full тулбар скрыт (чистая доска); выход — по Esc.
 */
export function CompactView({
  hosts,
  usage,
  label,
  forceFull = false,
  onExitFull,
}: {
  hosts: Map<string, HostState>;
  usage: ClaudeUsage | null;
  label: CompactLabel;
  forceFull?: boolean;
  onExitFull?: () => void;
}) {
  const cards = compactCards([...hosts.values()]);
  const { isSupported, isFullscreen } = useFullscreen();
  const full = isFullscreen || forceFull; // fullscreen look (real OR forced)

  // Measure the grid area so we can tile all cards across the full screen.
  const gridRef = useRef<HTMLDivElement>(null);
  const [size, setSize] = useState({ w: 0, h: 0 });
  useEffect(() => {
    const el = gridRef.current;
    if (!el) return;
    const ro = new ResizeObserver(([entry]) => {
      const r = entry.contentRect;
      setSize({ w: r.width, h: r.height });
    });
    ro.observe(el);
    return () => ro.disconnect();
  }, []);

  // On /compact/full without real fullscreen, try to enter it — immediately (works in
  // kiosk/allowed contexts) and on the first click (browsers require a user gesture).
  useEffect(() => {
    if (!forceFull || !isSupported || isFullscreen) return;
    const enter = () => void document.documentElement.requestFullscreen().catch(() => {});
    enter();
    window.addEventListener("pointerdown", enter, { once: true });
    return () => window.removeEventListener("pointerdown", enter);
  }, [forceFull, isSupported, isFullscreen]);

  // /compact/full has no toolbar, so Escape is the way out of the forced view.
  useEffect(() => {
    if (!forceFull) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== "Escape") return;
      if (document.fullscreenElement) void document.exitFullscreen().catch(() => {});
      onExitFull?.();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [forceFull, onExitFull]);

  function onFsButton() {
    if (isFullscreen) void document.exitFullscreen().catch(() => {});
    else void document.documentElement.requestFullscreen().catch(() => {});
  }

  // Квота занимает такую же ячейку, как карточка агента, — значит и в раскладку
  // она входит наравне с ними, иначе последняя строка съезжает.
  const showQuota = hasUsage(usage);
  const tiles = cards.length + (showQuota ? 1 : 0);

  const fill = full && tiles > 0 && size.w > 0 && size.h > 0;
  const cols = fill ? bestColumns(tiles, size.w, size.h, GAP) : 0;
  const rows = cols ? Math.ceil(tiles / cols) : 0;

  // In fullscreen, scale card text to the cell size (smaller side drives it, so text fits).
  let nameSize = 0;
  let hostSize = 0;
  let taskSize = 0;
  if (fill) {
    const cellW = (size.w - GAP * (cols - 1)) / cols;
    const cellH = (size.h - GAP * (rows - 1)) / rows;
    const unit = Math.min(cellW, cellH);
    nameSize = clamp(18, Math.round(unit * 0.16), 56);
    hostSize = clamp(11, Math.round(nameSize * 0.42), 22);
    taskSize = clamp(11, Math.round(nameSize * 0.5), 28);
  }

  return (
    <div
      className={cn(
        full
          ? "flex h-screen flex-col px-[18px] pb-[18px] pt-[18px]"
          : "mx-auto max-w-[1400px] px-[18px] pb-12 pt-[18px]",
      )}
    >
      {!forceFull && (
        <div className="mb-3 flex shrink-0 justify-end">
          {isSupported ? (
            <button
              onClick={onFsButton}
              title={full ? "Exit fullscreen" : "Fullscreen"}
              aria-label={full ? "Exit fullscreen" : "Fullscreen"}
              className="flex items-center gap-1.5 rounded-md border border-line px-2 py-1 font-sans text-[12px] text-muted transition-colors hover:text-ink-2"
            >
              {full ? <Minimize className="h-3.5 w-3.5" /> : <Maximize className="h-3.5 w-3.5" />}
              {full ? "Exit fullscreen" : "Fullscreen"}
            </button>
          ) : (
            <span
              className="font-mono text-[11px] text-muted-2"
              title="This browser or page context doesn't allow the Fullscreen API"
            >
              Fullscreen unavailable
            </span>
          )}
        </div>
      )}
      <div
        ref={gridRef}
        className={cn("grid gap-[14px]", full && "min-h-0 flex-1")}
        style={
          fill
            ? {
                gridTemplateColumns: `repeat(${cols}, 1fr)`,
                gridTemplateRows: `repeat(${rows}, 1fr)`,
              }
            : { gridTemplateColumns: "repeat(auto-fill, minmax(230px, 1fr))" }
        }
      >
        {tiles === 0 ? (
          <div className="col-span-full py-24 text-center font-mono text-[13px] text-muted-2">
            — no agents —
          </div>
        ) : (
          cards.map((c) => {
            const name = label === "task" ? c.task : c.project;
            const showTask = label === "both" && c.task !== name;
            return (
              <div
                key={c.key}
                className={cn(
                  "flex flex-col gap-2 overflow-hidden rounded-[10px]",
                  full ? "h-full min-h-0" : "h-32",
                )}
                style={{
                  padding: "14px 16px",
                  background: hex(c.color, 0.1),
                  border: `1px solid ${hex(c.color, 0.28)}`,
                  opacity: c.opacity,
                }}
              >
                <span
                  className="shrink-0 font-mono text-[12px] text-muted"
                  style={fill ? { fontSize: hostSize } : undefined}
                >
                  {c.host}
                </span>
                <span
                  className="break-all font-mono text-[16px] font-bold leading-[1.3] line-clamp-3"
                  style={fill ? { color: c.color, fontSize: nameSize } : { color: c.color }}
                >
                  {name}
                </span>
                {showTask && (
                  <span
                    className="break-all font-mono text-[12px] leading-tight text-muted line-clamp-1"
                    style={fill ? { fontSize: taskSize } : undefined}
                  >
                    {c.task}
                  </span>
                )}
              </div>
            );
          })
        )}
        {showQuota && (
          // Последней плиткой — место под неё есть всегда, и цифры читаются издалека.
          <UsageTile
            usage={usage}
            full={full}
            fill={fill}
            nameSize={nameSize}
            hostSize={hostSize}
            taskSize={taskSize}
          />
        )}
      </div>
    </div>
  );
}

import { useEffect, useRef, useState } from "react";
import { Maximize, Minimize } from "lucide-react";
import type { HostState } from "@/lib/types";
import type { CompactLabel } from "@/lib/prefs";
import { compactCards } from "@/lib/sort";
import { hex } from "@/lib/theme";
import { cn } from "@/lib/utils";
import { useFullscreen } from "@/hooks/useFullscreen";

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
 * карточек, по одной на агента. Статус передаётся ТОЛЬКО цветом (фон 10%,
 * рамка 28%, имя — полный цвет). Read-only. В фуллскрине сетка растягивается на
 * весь экран (cols×rows под число карточек). Данные — те же, что у Monitor.
 */
export function CompactView({
  hosts,
  label,
}: {
  hosts: Map<string, HostState>;
  label: CompactLabel;
}) {
  const cards = compactCards([...hosts.values()]);
  const { isSupported, isFullscreen, toggle } = useFullscreen();

  // Measure the grid area so we can tile all cards across the full screen in fullscreen.
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

  const fill = isFullscreen && cards.length > 0 && size.w > 0 && size.h > 0;
  const cols = fill ? bestColumns(cards.length, size.w, size.h, GAP) : 0;
  const rows = cols ? Math.ceil(cards.length / cols) : 0;

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
        isFullscreen
          ? "flex h-screen flex-col px-[18px] pb-[18px] pt-[18px]"
          : "mx-auto max-w-[1400px] px-[18px] pb-12 pt-[18px]",
      )}
    >
      <div className="mb-3 flex shrink-0 justify-end">
        {isSupported ? (
          <button
            onClick={toggle}
            title={isFullscreen ? "Exit fullscreen" : "Fullscreen"}
            aria-label={isFullscreen ? "Exit fullscreen" : "Fullscreen"}
            className="flex items-center gap-1.5 rounded-md border border-line px-2 py-1 font-sans text-[12px] text-muted transition-colors hover:text-ink-2"
          >
            {isFullscreen ? (
              <Minimize className="h-3.5 w-3.5" />
            ) : (
              <Maximize className="h-3.5 w-3.5" />
            )}
            {isFullscreen ? "Exit fullscreen" : "Fullscreen"}
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
      <div
        ref={gridRef}
        className={cn("grid gap-[14px]", isFullscreen && "min-h-0 flex-1")}
        style={
          fill
            ? {
                gridTemplateColumns: `repeat(${cols}, 1fr)`,
                gridTemplateRows: `repeat(${rows}, 1fr)`,
              }
            : { gridTemplateColumns: "repeat(auto-fill, minmax(230px, 1fr))" }
        }
      >
        {cards.length === 0 ? (
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
                  isFullscreen ? "h-full min-h-0" : "h-32",
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
      </div>
    </div>
  );
}

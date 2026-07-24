import { Maximize, Minimize } from "lucide-react";
import type { HostState } from "@/lib/types";
import type { CompactLabel } from "@/lib/prefs";
import { compactCards } from "@/lib/sort";
import { hex } from "@/lib/theme";
import { useFullscreen } from "@/hooks/useFullscreen";

/**
 * Compact — «плоский» экран для маленьких дисплеев (~7"): сетка одинаковых
 * карточек, по одной на агента. Статус передаётся ТОЛЬКО цветом (фон 10%,
 * рамка 28%, имя — полный цвет). Read-only. Что показывать крупным (проект/
 * задача/оба) задаётся настройкой `label`. Данные — те же, что у Monitor.
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

  return (
    <div className="mx-auto max-w-[1400px] px-[18px] pb-12 pt-[18px]">
      <div className="mb-3 flex justify-end">
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
        className="grid gap-[14px]"
        style={{ gridTemplateColumns: "repeat(auto-fill, minmax(230px, 1fr))" }}
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
                className="flex h-32 flex-col gap-2 overflow-hidden rounded-[10px]"
                style={{
                  padding: "14px 16px",
                  background: hex(c.color, 0.1),
                  border: `1px solid ${hex(c.color, 0.28)}`,
                  opacity: c.opacity,
                }}
              >
                <span className="shrink-0 font-mono text-[12px] text-muted">{c.host}</span>
                <span
                  className="break-all font-mono text-[16px] font-bold leading-[1.3] line-clamp-3"
                  style={{ color: c.color }}
                >
                  {name}
                </span>
                {showTask && (
                  <span className="break-all font-mono text-[12px] leading-tight text-muted line-clamp-1">
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

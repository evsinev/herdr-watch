import type { Health } from "./types";

// Точные цвета статусов/health и приоритеты из design-logic.js (тёмная тема).

export interface StatusStyle {
  color: string;
  priority: number;
}

export const STATUS: Record<string, StatusStyle> = {
  blocked: { color: "#E24B4A", priority: 5 },
  working: { color: "#EF9F27", priority: 4 },
  done: { color: "#378ADD", priority: 3 },
  idle: { color: "#639922", priority: 2 },
  unknown: { color: "#888780", priority: 1 },
};

export function statusOf(status?: string | null): StatusStyle {
  const key = (status ?? "unknown").toLowerCase();
  return STATUS[key] ?? STATUS.unknown;
}

export const HEALTH: Record<string, { color: string; label: string }> = {
  connected: { color: "#639922", label: "connected" },
  degraded: { color: "#EF9F27", label: "degraded" },
  unreachable: { color: "#888780", label: "unreachable" },
};

export function healthOf(health?: Health | string | null) {
  const key = (health ?? "unreachable").toLowerCase();
  return HEALTH[key] ?? HEALTH.unreachable;
}

export const FLAG = {
  detached: { color: "#E24B4A", label: "detached" },
  prunable: { color: "#EF9F27", label: "prunable" },
} as const;

/**
 * Полосы утилизации квоты Claude. ЗЕРКАЛО backend `usage/UsageSeverity.java`
 * (severityCode в Snapshot API) — значения обязаны совпадать, правим парой.
 */
export const USAGE_BANDS = {
  warnAt: 70,
  criticalAt: 90,
  exhaustedAt: 100,
} as const;

/** Цвет полосы квоты — из тех же токенов статусов, без новых hex'ов. */
export function usageColor(usedPercent: number): string {
  if (usedPercent >= USAGE_BANDS.criticalAt) return STATUS.blocked.color;
  if (usedPercent >= USAGE_BANDS.warnAt) return STATUS.working.color;
  return STATUS.idle.color;
}

/** #rrggbb + alpha → rgba() (helper hex() из design-logic.js). */
export function hex(color: string, alpha: number): string {
  const c = color.replace("#", "");
  const r = parseInt(c.slice(0, 2), 16);
  const g = parseInt(c.slice(2, 4), 16);
  const b = parseInt(c.slice(4, 6), 16);
  return `rgba(${r}, ${g}, ${b}, ${alpha})`;
}

// Готовые стили бейджа для статуса/health/флага (bg 10%, border 28%/30%).
export function badgeStyle(color: string, borderAlpha = 0.28) {
  return {
    color,
    background: hex(color, 0.1),
    border: `1px solid ${hex(color, borderAlpha)}`,
  };
}

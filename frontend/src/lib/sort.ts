import type { HostState } from "./types";
import { statusOf } from "./theme";

/**
 * Приоритет rollup хоста: максимальный приоритет статуса среди воркспейсов
 * (их agentStatus считает сам herdr) и агентов. 0 — если состояний нет.
 */
export function hostRollupPriority(h: HostState): number {
  let max = 0;
  for (const ws of h.workspaces) {
    if (ws.agentStatus) max = Math.max(max, statusOf(ws.agentStatus).priority);
  }
  for (const a of h.agents) {
    if (a.status) max = Math.max(max, statusOf(a.status).priority);
  }
  return max;
}

/** Максимальный приоритет среди агентов одного воркспейса (для точки воркспейса). */
export function workspacePriority(agentStatuses: (string | null)[]): number {
  let max = 0;
  for (const s of agentStatuses) if (s) max = Math.max(max, statusOf(s).priority);
  return max;
}

/**
 * Сортировка хостов: unreachable уходят в конец, остальные — по убыванию
 * приоритета rollup (blocked сверху). См. design-logic.js.
 */
export function sortHosts(hosts: HostState[]): HostState[] {
  return [...hosts].sort((a, b) => {
    const au = a.health === "UNREACHABLE" ? 1 : 0;
    const bu = b.health === "UNREACHABLE" ? 1 : 0;
    if (au !== bu) return au - bu;
    const byPriority = hostRollupPriority(b) - hostRollupPriority(a);
    if (byPriority !== 0) return byPriority;
    return a.id.localeCompare(b.id); // стабильный вторичный порядок
  });
}

/** Одна карточка Compact-экрана (по одной на агента). */
export interface CompactCard {
  key: string;
  host: string;
  project: string; // label воркспейса (или его id) — «имя проекта»
  task: string; // terminal_title_stripped агента — «ветка/задача»
  color: string; // полный цвет статуса
  priority: number;
  opacity: number; // 0.6 для агентов недоступного хоста
}

/**
 * Производные карточки Compact: разворачиваем всех агентов по всем хостам и
 * сортируем по приоритету статуса (blocked > working > done > idle > unknown).
 * Что показывать (project/task) решает уже сам экран — здесь отдаём оба поля.
 */
export function compactCards(hosts: HostState[]): CompactCard[] {
  const cards: CompactCard[] = [];
  for (const h of hosts) {
    const opacity = h.health === "UNREACHABLE" ? 0.6 : 1;
    const wsLabel = new Map(
      h.workspaces.map((w) => [w.id, w.label && w.label.trim() ? w.label : w.id]),
    );
    for (const a of h.agents) {
      const s = statusOf(a.status);
      const task = a.title ?? a.paneId ?? "—";
      const project =
        (a.workspaceId != null ? wsLabel.get(a.workspaceId) : undefined) ?? task;
      cards.push({
        key: `${h.id}:${a.paneId ?? a.title ?? ""}`,
        host: h.id,
        project,
        task,
        color: s.color,
        priority: s.priority,
        opacity,
      });
    }
  }
  cards.sort(
    (a, b) =>
      b.priority - a.priority ||
      a.host.localeCompare(b.host) ||
      a.project.localeCompare(b.project),
  );
  return cards;
}

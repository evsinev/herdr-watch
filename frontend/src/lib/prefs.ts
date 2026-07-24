// Клиентские настройки отображения (per-browser, localStorage).

export type CompactLabel = "project" | "task" | "both";

const KEY = "herdr-watch.compactLabel";
const VALID: readonly CompactLabel[] = ["project", "task", "both"];

export function loadCompactLabel(): CompactLabel {
  try {
    const v = localStorage.getItem(KEY);
    if (v && (VALID as readonly string[]).includes(v)) return v as CompactLabel;
  } catch {
    // localStorage может быть недоступен — тихо возвращаем дефолт
  }
  return "project";
}

export function saveCompactLabel(v: CompactLabel): void {
  try {
    localStorage.setItem(KEY, v);
  } catch {
    // игнорируем — настройка просто не сохранится
  }
}

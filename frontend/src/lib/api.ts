import type { ApiErrors, ClaudeUsage, HostRequest, ServerView } from "./types";

const BASE = "/api/servers";

/** Ошибка CRUD с field-level сообщениями от бэкенда ({ errors: {...} }). */
export class ApiError extends Error {
  errors: Record<string, string>;
  constructor(errors: Record<string, string>) {
    super("request failed");
    this.name = "ApiError";
    this.errors = errors;
  }
}

async function readBody(res: Response): Promise<unknown> {
  const text = await res.text();
  return text ? JSON.parse(text) : null;
}

async function ensureOk<T>(res: Response): Promise<T> {
  const body = await readBody(res);
  if (!res.ok) {
    const errors = (body as ApiErrors | null)?.errors ?? {
      _: `Request failed (HTTP ${res.status})`,
    };
    throw new ApiError(errors);
  }
  return body as T;
}

export async function getServers(): Promise<ServerView[]> {
  return ensureOk<ServerView[]>(await fetch(BASE));
}

/**
 * Текущая квота Claude. SSE шлёт `claude_usage` только при ИЗМЕНЕНИИ, поэтому
 * только что подключившемуся клиенту первый снапшот нужно взять здесь.
 */
export async function getClaudeUsage(): Promise<ClaudeUsage> {
  return ensureOk<ClaudeUsage>(await fetch("/api/claude-usage"));
}

export async function createServer(req: HostRequest): Promise<ServerView> {
  return ensureOk<ServerView>(
    await fetch(BASE, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(req),
    }),
  );
}

export async function updateServer(id: string, req: HostRequest): Promise<ServerView> {
  return ensureOk<ServerView>(
    await fetch(`${BASE}/${encodeURIComponent(id)}`, {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(req),
    }),
  );
}

export async function deleteServer(id: string): Promise<void> {
  const res = await fetch(`${BASE}/${encodeURIComponent(id)}`, { method: "DELETE" });
  if (!res.ok) {
    const body = (await readBody(res)) as ApiErrors | null;
    throw new ApiError(body?.errors ?? { _: `Request failed (HTTP ${res.status})` });
  }
}

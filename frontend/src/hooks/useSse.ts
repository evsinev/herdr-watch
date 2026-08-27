import { useEffect, useState } from "react";
import type { ClaudeUsage, HostState, StreamEvent } from "@/lib/types";
import { getClaudeUsage } from "@/lib/api";

export interface SseState {
  hosts: Map<string, HostState>;
  /** Квота Claude — свойство аккаунта, поэтому лежит РЯДОМ с картой хостов, а не в ней. */
  usage: ClaudeUsage | null;
  connected: boolean;
}

/**
 * Единственный источник живых данных Monitor: EventSource('/api/stream').
 * Бэкенд шлёт безымянные message-события вида { type, data } — слушаем onmessage
 * и разбираем по type. EventSource переподключается сам и снова получает snapshot.
 */
export function useSse(): SseState {
  const [hosts, setHosts] = useState<Map<string, HostState>>(new Map());
  const [usage, setUsage] = useState<ClaudeUsage | null>(null);
  const [connected, setConnected] = useState(false);

  useEffect(() => {
    const es = new EventSource("/api/stream");

    es.onopen = () => {
      setConnected(true);
      // claude_usage приходит только при изменении — стартовое значение берём по REST.
      getClaudeUsage()
        .then(setUsage)
        .catch(() => {});
    };
    es.onerror = () => setConnected(false); // авто-reconnect встроен в EventSource

    es.onmessage = (e) => {
      let ev: StreamEvent;
      try {
        ev = JSON.parse(e.data) as StreamEvent;
      } catch {
        return;
      }
      if (ev.type === "snapshot") {
        setHosts(new Map(ev.data.map((h) => [h.id, h])));
      } else if (ev.type === "host_update") {
        const h = ev.data;
        setHosts((prev) => new Map(prev).set(h.id, h));
      } else if (ev.type === "claude_usage") {
        setUsage(ev.data);
      } else if (ev.type === "host_remove") {
        const id = ev.data.id;
        setHosts((prev) => {
          const next = new Map(prev);
          next.delete(id);
          return next;
        });
      }
    };

    return () => es.close();
  }, []);

  return { hosts, usage, connected };
}

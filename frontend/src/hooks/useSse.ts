import { useEffect, useState } from "react";
import type { HostState, StreamEvent } from "@/lib/types";

export interface SseState {
  hosts: Map<string, HostState>;
  connected: boolean;
}

/**
 * Единственный источник живых данных Monitor: EventSource('/api/stream').
 * Бэкенд шлёт безымянные message-события вида { type, data } — слушаем onmessage
 * и разбираем по type. EventSource переподключается сам и снова получает snapshot.
 */
export function useSse(): SseState {
  const [hosts, setHosts] = useState<Map<string, HostState>>(new Map());
  const [connected, setConnected] = useState(false);

  useEffect(() => {
    const es = new EventSource("/api/stream");

    es.onopen = () => setConnected(true);
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

  return { hosts, connected };
}

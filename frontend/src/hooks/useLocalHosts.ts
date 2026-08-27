import { useEffect, useState } from "react";
import { getServers } from "@/lib/api";

/**
 * id хостов, помеченных `local` (herdr текущего пользователя, без ssh). SSE-модель
 * хоста этого признака не несёт — он часть конфигурации, а не состояния, — поэтому
 * берём его из /api/servers. Пересчитывается, когда меняется САМ НАБОР хостов
 * (добавили/удалили в Settings), а не на каждый кадр.
 */
export function useLocalHosts(hostIds: string[]): Set<string> {
  const [local, setLocal] = useState<Set<string>>(new Set());
  const key = [...hostIds].sort().join(",");

  useEffect(() => {
    let alive = true;
    getServers()
      .then((servers) => {
        if (alive) setLocal(new Set(servers.filter((s) => s.local).map((s) => s.id)));
      })
      .catch(() => {});
    return () => {
      alive = false;
    };
  }, [key]);

  return local;
}

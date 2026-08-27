import { useEffect, useState } from "react";
import { Header } from "@/components/Header";
import { MonitorView } from "@/components/monitor/MonitorView";
import { CompactView } from "@/components/compact/CompactView";
import { SettingsView } from "@/components/settings/SettingsView";
import { useSse } from "@/hooks/useSse";
import { useLocalHosts } from "@/hooks/useLocalHosts";
import { loadCompactLabel, saveCompactLabel } from "@/lib/prefs";

export type View = "monitor" | "compact" | "settings";

export default function App() {
  // /compact/full → open Compact and render it fullscreen-style right away.
  const initialFull =
    typeof window !== "undefined" &&
    window.location.pathname.replace(/\/+$/, "") === "/compact/full";
  const [view, setView] = useState<View>(initialFull ? "compact" : "monitor");
  const [forceFull, setForceFull] = useState(initialFull);
  const [compactLabel, setCompactLabel] = useState(loadCompactLabel);
  const { hosts, usage, connected } = useSse();
  // Квота — свойство аккаунта: рисуем её только на карточке локального хоста.
  const localHosts = useLocalHosts([...hosts.keys()]);

  useEffect(() => saveCompactLabel(compactLabel), [compactLabel]);

  // "offline" с грейс-периодом: не мигаем на старте, показываем обрыв через ~1.5с
  const [offline, setOffline] = useState(false);
  useEffect(() => {
    if (connected) {
      setOffline(false);
      return;
    }
    const t = setTimeout(() => setOffline(true), 1500);
    return () => clearTimeout(t);
  }, [connected]);

  // Reflect offline onto <html> so CSS can keep the header (its disconnect banner)
  // visible even in fullscreen, where the header is otherwise hidden.
  useEffect(() => {
    document.documentElement.toggleAttribute("data-offline", offline);
  }, [offline]);

  const onView = (v: View) => {
    setForceFull(false);
    setView(v);
  };
  const exitFull = () => {
    setForceFull(false);
    if (window.location.pathname !== "/") history.replaceState(null, "", "/");
  };

  // In forced-fullscreen (/compact/full) don't render the header at all — a clean board.
  // Exception: when offline, show it so its "disconnected" banner is visible.
  const showHeader = !forceFull || offline;

  return (
    <div className="min-h-screen bg-page text-ink">
      {showHeader && <Header view={view} onView={onView} hosts={hosts} offline={offline} />}
      <main>
        {view === "monitor" && (
          <MonitorView hosts={hosts} usage={usage} localHosts={localHosts} />
        )}
        {view === "compact" && (
          <CompactView
            hosts={hosts}
            usage={usage}
            label={compactLabel}
            forceFull={forceFull}
            onExitFull={exitFull}
          />
        )}
        {view === "settings" && (
          <SettingsView compactLabel={compactLabel} onCompactLabel={setCompactLabel} />
        )}
      </main>
    </div>
  );
}

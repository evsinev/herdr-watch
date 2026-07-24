import { useEffect, useState } from "react";
import { Header } from "@/components/Header";
import { MonitorView } from "@/components/monitor/MonitorView";
import { CompactView } from "@/components/compact/CompactView";
import { SettingsView } from "@/components/settings/SettingsView";
import { useSse } from "@/hooks/useSse";
import { loadCompactLabel, saveCompactLabel } from "@/lib/prefs";

export type View = "monitor" | "compact" | "settings";

export default function App() {
  const [view, setView] = useState<View>("monitor");
  const [compactLabel, setCompactLabel] = useState(loadCompactLabel);
  const { hosts, connected } = useSse();

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

  return (
    <div className="min-h-screen bg-page text-ink">
      <Header view={view} onView={setView} hosts={hosts} offline={offline} />
      <main>
        {view === "monitor" && <MonitorView hosts={hosts} />}
        {view === "compact" && <CompactView hosts={hosts} label={compactLabel} />}
        {view === "settings" && (
          <SettingsView compactLabel={compactLabel} onCompactLabel={setCompactLabel} />
        )}
      </main>
    </div>
  );
}

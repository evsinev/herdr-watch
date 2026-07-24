import { useCallback, useEffect, useState } from "react";
import { Plus } from "lucide-react";
import { deleteServer, getServers, updateServer } from "@/lib/api";
import type { ServerView } from "@/lib/types";
import { Button } from "@/components/ui/button";
import { HostRow } from "./HostRow";
import { HostFormModal } from "./HostFormModal";
import { cn } from "@/lib/utils";
import type { CompactLabel } from "@/lib/prefs";

// колонки таблицы: id · host · herdr-path · poll · reconnect · enabled · статус/действия
const COLS = "1fr 1.3fr 1.7fr 0.7fr 1fr 0.8fr 250px";

export function SettingsView({
  compactLabel,
  onCompactLabel,
}: {
  compactLabel: CompactLabel;
  onCompactLabel: (v: CompactLabel) => void;
}) {
  const [servers, setServers] = useState<ServerView[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [modalOpen, setModalOpen] = useState(false);
  const [editing, setEditing] = useState<ServerView | null>(null);

  const refetch = useCallback(async () => {
    try {
      setServers(await getServers());
      setLoadError(null);
    } catch {
      setLoadError("Could not load hosts — is the backend running on :8080?");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void refetch();
  }, [refetch]);

  const openAdd = () => {
    setEditing(null);
    setModalOpen(true);
  };
  const openEdit = (s: ServerView) => {
    setEditing(s);
    setModalOpen(true);
  };

  const onRemove = async (id: string) => {
    try {
      await deleteServer(id);
    } finally {
      await refetch();
    }
  };

  const onToggle = async (s: ServerView, enabled: boolean) => {
    try {
      await updateServer(s.id, {
        id: s.id,
        host: s.host,
        herdrPath: s.herdrPath,
        pollInterval: s.pollInterval,
        reconnectDelay: s.reconnectDelay,
        enabled,
        sshExtraOpts: s.sshExtraOpts,
        local: s.local,
      });
    } finally {
      await refetch();
    }
  };

  const isEmpty = !loading && servers.length === 0;

  return (
    <div className="mx-auto max-w-[1200px] px-7 pb-16 pt-6">
      <div className="mb-5 flex items-start gap-4">
        <div>
          <h1 className="font-sans text-[17px] font-semibold text-ink">Hosts</h1>
          <p className="mt-1 max-w-[640px] font-sans text-[12.5px] leading-relaxed text-muted">
            Servers herdr-watch connects to over SSH. Changes save to the config and
            take effect by (re)connecting that host — no full restart.
          </p>
        </div>
        {!isEmpty && (
          <Button className="ml-auto shrink-0" onClick={openAdd}>
            <Plus className="h-4 w-4" /> Add host
          </Button>
        )}
      </div>

      {/* Настройка отображения Compact-экрана (клиентская, localStorage) */}
      <div className="mb-6 flex flex-wrap items-center gap-3">
        <span className="font-sans text-[12.5px] text-muted">Compact card label</span>
        <div className="inline-flex overflow-hidden rounded-md border border-line-strong">
          {(
            [
              ["project", "Project"],
              ["task", "Task"],
              ["both", "Project + Task"],
            ] as const
          ).map(([val, label], i) => (
            <button
              key={val}
              onClick={() => onCompactLabel(val)}
              className={cn(
                "px-3 py-1.5 font-sans text-[12.5px] transition-colors",
                i > 0 && "border-l border-line-strong",
                compactLabel === val
                  ? "bg-accent text-white"
                  : "text-muted hover:text-ink-2",
              )}
            >
              {label}
            </button>
          ))}
        </div>
      </div>

      {loadError && (
        <div className="mb-4 rounded-md border border-danger/40 bg-danger/10 px-4 py-2.5 font-mono text-[12px] text-danger">
          {loadError}
        </div>
      )}

      {isEmpty ? (
        <EmptyState onAdd={openAdd} />
      ) : (
        <div className="overflow-x-auto">
          <div className="min-w-[880px] overflow-hidden rounded-lg border border-line bg-card">
            <div
              className="grid gap-3 border-b border-line px-4 py-2.5 font-mono text-[10.5px] uppercase tracking-[0.08em] text-muted-2"
              style={{ gridTemplateColumns: COLS }}
            >
              <span>id</span>
              <span>host</span>
              <span>herdr-path</span>
              <span>poll</span>
              <span>reconnect</span>
              <span>enabled</span>
              <span className="text-right">status</span>
            </div>
            {servers.map((s) => (
              <HostRow
                key={s.id}
                server={s}
                cols={COLS}
                onEdit={() => openEdit(s)}
                onRemove={() => onRemove(s.id)}
                onToggle={(enabled) => onToggle(s, enabled)}
              />
            ))}
          </div>
        </div>
      )}

      <HostFormModal
        open={modalOpen}
        onOpenChange={setModalOpen}
        editing={editing}
        onSaved={async () => {
          setModalOpen(false);
          await refetch();
        }}
      />
    </div>
  );
}

function EmptyState({ onAdd }: { onAdd: () => void }) {
  return (
    <div className="flex flex-col items-center gap-3 rounded-lg border border-dashed border-line-strong bg-card/40 px-6 py-20 text-center">
      <div className="font-mono text-[13px] text-muted-2">no hosts configured</div>
      <div className="font-sans text-[12.5px] text-muted-3">
        Add your first host to start watching the fleet
      </div>
      <Button className="mt-2" onClick={onAdd}>
        <Plus className="h-4 w-4" /> Add your first host
      </Button>
    </div>
  );
}

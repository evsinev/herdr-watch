import { useEffect, useState, type ReactNode } from "react";
import { ApiError, createServer, updateServer } from "@/lib/api";
import type { DataSourceMode, HostRequest, ServerView } from "@/lib/types";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Switch } from "@/components/ui/switch";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";

interface Props {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  editing: ServerView | null;
  onSaved: () => void;
}

interface FormState {
  id: string;
  host: string;
  herdrPath: string;
  poll: string;
  reconnect: string;
  enabled: boolean;
  local: boolean;
  dataSource: DataSourceMode;
  socketPath: string;
}

const DEFAULTS: FormState = {
  id: "",
  host: "",
  herdrPath: "herdr",
  poll: "2",
  reconnect: "5",
  enabled: true,
  local: false,
  dataSource: "command",
  socketPath: "",
};

export function HostFormModal({ open, onOpenChange, editing, onSaved }: Props) {
  const [form, setForm] = useState<FormState>(DEFAULTS);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    if (!open) return;
    setErrors({});
    if (editing) {
      setForm({
        id: editing.id,
        host: editing.host,
        herdrPath: editing.herdrPath || "herdr",
        poll: String(editing.pollInterval),
        reconnect: String(editing.reconnectDelay),
        enabled: editing.enabled,
        local: editing.local,
        dataSource: editing.dataSource,
        socketPath: editing.socketPath ?? "",
      });
    } else {
      setForm(DEFAULTS);
    }
  }, [open, editing]);

  const set = (patch: Partial<FormState>) => setForm((f) => ({ ...f, ...patch }));

  const validate = (): Record<string, string> => {
    const e: Record<string, string> = {};
    if (!form.id.trim()) e.id = "Enter a name for this host";
    if (!form.local && !form.host.trim()) e.host = "Enter an ssh target";
    if (!/^\d+$/.test(form.poll.trim()) || +form.poll < 1)
      e.poll = "Use a positive whole number";
    if (!/^\d+$/.test(form.reconnect.trim()) || +form.reconnect < 1)
      e.reconnect = "Use a positive whole number";
    return e;
  };

  const submit = async () => {
    const e = validate();
    if (Object.keys(e).length) {
      setErrors(e);
      return;
    }
    setSaving(true);
    const req: HostRequest = {
      id: editing ? editing.id : form.id.trim(),
      host: form.host.trim(),
      herdrPath: form.herdrPath.trim() || "herdr",
      pollInterval: Number(form.poll),
      reconnectDelay: Number(form.reconnect),
      enabled: form.enabled,
      local: form.local,
      dataSource: form.dataSource,
      socketPath:
        form.dataSource === "socket" ? form.socketPath.trim() || null : null,
    };
    try {
      if (editing) await updateServer(editing.id, req);
      else await createServer(req);
      onSaved();
    } catch (err) {
      if (err instanceof ApiError) setErrors(mapServerErrors(err.errors));
      else setErrors({ _: "Save failed — check the backend logs." });
    } finally {
      setSaving(false);
    }
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <div className="p-5">
          <DialogHeader>
            <DialogTitle>{editing ? `Edit ${editing.id}` : "Add host"}</DialogTitle>
            <DialogDescription>
              Saves to config · takes effect on (re)connect
            </DialogDescription>
          </DialogHeader>

          <div className="mt-4 flex flex-col gap-3.5">
            <Field label="id" hint="logical name shown in the UI" error={errors.id}>
              <Input
                value={form.id}
                placeholder="m3-local"
                invalid={!!errors.id}
                readOnly={!!editing}
                disabled={!!editing}
                onChange={(e) => set({ id: e.target.value })}
              />
            </Field>

            <label className="flex cursor-pointer items-center gap-2.5">
              <Switch
                checked={form.local}
                onCheckedChange={(v) => set({ local: v })}
              />
              <span className="font-sans text-[12.5px] text-ink-2">
                local — read this machine's herdr (no SSH)
              </span>
            </label>

            <Field label="data source" hint="how to read herdr on this host">
              <select
                value={form.dataSource}
                onChange={(e) => set({ dataSource: e.target.value as DataSourceMode })}
                className="h-9 w-full rounded-md border border-line-strong bg-field px-3 font-mono text-[13px] text-ink transition-colors focus-visible:border-accent focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-accent"
              >
                <option value="command">command — spawn herdr CLI (bash/ssh + jq)</option>
                <option value="socket">socket — connect to herdr's unix socket directly</option>
              </select>
            </Field>

            <Field
              label="host"
              hint={form.local ? "not used for a local host" : "ssh alias or user@host"}
              error={errors.host}
            >
              <Input
                value={form.local ? "" : form.host}
                placeholder={form.local ? "local" : "dqa1  ·  deploy@10.0.0.4"}
                invalid={!!errors.host}
                disabled={form.local}
                onChange={(e) => set({ host: e.target.value })}
              />
            </Field>

            {form.dataSource === "command" ? (
              <Field label="herdr-path" hint="path to herdr on the host">
                <Input
                  value={form.herdrPath}
                  placeholder="herdr"
                  onChange={(e) => set({ herdrPath: e.target.value })}
                />
              </Field>
            ) : (
              <Field
                label="socket path"
                hint={
                  form.local
                    ? "blank → ~/.config/herdr/herdr.sock"
                    : "herdr.sock path on the remote host"
                }
              >
                <Input
                  value={form.socketPath}
                  placeholder="~/.config/herdr/herdr.sock"
                  onChange={(e) => set({ socketPath: e.target.value })}
                />
              </Field>
            )}

            <div className="flex gap-3">
              <Field className="flex-1" label="poll interval (s)" error={errors.poll}>
                <Input
                  value={form.poll}
                  placeholder="2"
                  inputMode="numeric"
                  invalid={!!errors.poll}
                  onChange={(e) => set({ poll: e.target.value })}
                />
              </Field>
              <Field
                className="flex-1"
                label="reconnect delay (s)"
                error={errors.reconnect}
              >
                <Input
                  value={form.reconnect}
                  placeholder="5"
                  inputMode="numeric"
                  invalid={!!errors.reconnect}
                  onChange={(e) => set({ reconnect: e.target.value })}
                />
              </Field>
            </div>

            <label className="flex cursor-pointer items-center gap-2.5 pt-1">
              <Switch
                checked={form.enabled}
                onCheckedChange={(v) => set({ enabled: v })}
              />
              <span className="font-sans text-[12.5px] text-ink-2">
                enabled — connect this host
              </span>
            </label>

            {errors._ && (
              <div className="font-mono text-[11.5px] text-danger">{errors._}</div>
            )}
          </div>

          <DialogFooter className="mt-6">
            <Button variant="outline" onClick={() => onOpenChange(false)} disabled={saving}>
              Cancel
            </Button>
            <Button onClick={submit} disabled={saving}>
              {editing ? "Save changes" : "Add host"}
            </Button>
          </DialogFooter>
        </div>
      </DialogContent>
    </Dialog>
  );
}

/** Серверные ключи (pollInterval/reconnectDelay) → ключи полей формы (poll/reconnect). */
function mapServerErrors(errors: Record<string, string>): Record<string, string> {
  const m: Record<string, string> = {};
  for (const [k, v] of Object.entries(errors)) {
    if (k === "pollInterval") m.poll = v;
    else if (k === "reconnectDelay") m.reconnect = v;
    else m[k] = v;
  }
  return m;
}

function Field({
  label,
  hint,
  error,
  className,
  children,
}: {
  label: string;
  hint?: string;
  error?: string;
  className?: string;
  children: ReactNode;
}) {
  return (
    <div className={className}>
      <div className="mb-1 flex items-baseline gap-2">
        <span className="font-sans text-[12px] text-ink-3">{label}</span>
        {hint && <span className="font-sans text-[11px] text-muted-3">{hint}</span>}
      </div>
      {children}
      {error && <div className="mt-1 font-mono text-[11.5px] text-danger">{error}</div>}
    </div>
  );
}

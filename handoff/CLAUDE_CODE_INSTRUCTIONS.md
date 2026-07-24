# herdr-watch — implementation handoff

You are building **herdr-watch**: a read-only web dashboard that aggregates the
state of multiple `herdr` servers (a terminal multiplexer for AI coding agents)
into one live monitor. One local machine plus several remote servers reached
over SSH. There is no federation between herdr servers — each is a separate
process with its own Unix socket — so herdr-watch fans out over SSH, polls each
host, and merges everything into one consistent view served to the browser over
SSE.

This package contains a **working backend skeleton**, an **approved dark-theme
UI mockup**, and a **reference React mock**. Your job is to finish the backend
(host CRUD + hot reconnect), build the real frontend from the mockup, and wire
them together into a runnable app.

---

## What's in this package

```
herdr-watch/
├── CLAUDE_CODE_INSTRUCTIONS.md   ← this file (the spec)
├── PROMPT.md                     ← the kickoff prompt
├── backend/                      ← Quarkus 3.15 / Java 21 — COMPILES, run as-is
│   ├── pom.xml
│   └── src/main/java/com/payneteasy/herdrwatch/
│       ├── HostsConfig.java      ← @ConfigMapping, hosts from application.yaml
│       ├── Registry.java         ← in-memory state + SSE broadcast bus
│       ├── model/Model.java      ← records: HostState, WorkspaceInfo, AgentInfo, WorktreeInfo
│       ├── source/SshSource.java ← ONE long-lived ssh session per host (core logic)
│       ├── source/SourceManager.java ← one virtual thread per enabled host
│       └── http/StreamResource.java, ServersResource.java ← SSE + REST
│   └── src/main/resources/application.yaml
├── design/
│   ├── design-bundle.html        ← the standalone approved mockup (open in a browser to see it)
│   ├── design-template.html      ← extracted HTML template (the exact visual structure)
│   └── design-logic.js           ← extracted component logic (data shape, colors, sort, validation)
└── reference/
    └── dashboard-mock.jsx        ← earlier React mock (data flow + SSE hook reference)
```

**Read all three design/reference files before writing frontend code.** The
mockup in `design/` is the visual source of truth; match it closely. The
`reference/dashboard-mock.jsx` shows the SSE wiring and the exact JSON shape the
backend emits.

---

## Architecture (do not change the shape)

```
local herdr ─┐
host dqa1 ──┼─► SshSource (1 ssh conn) ─► Registry ─► SSE /api/stream ─► React dashboard
host dqa2 ──┘   remote poll loop          in-memory    REST /api/servers (CRUD)
```

- **SshSource** — one long-lived `ssh` process per host. Inside that single
  session a remote `while true; do …; sleep N; done` loop runs and emits one
  NDJSON frame per line. The Java side reads stdout line by line. On drop →
  mark host UNREACHABLE, wait `reconnect-delay`, reconnect. This is a direct
  port of a proven bash script; **keep this model, do not switch to per-poll
  ssh invocations.**
- **Registry** — single source of truth. Holds last-known state per host plus
  connection health. The dashboard talks ONLY to the Registry, never to hosts
  directly. When a host drops, the UI shows it as unreachable with grayed-out
  last-known state — it does not freeze or vanish.
- **SSE** — on connect: full snapshot (`type: "snapshot"`), then deltas
  (`type: "host_update"`). Browser `EventSource` auto-reconnects.

---

## Real herdr data schemas (verified against live output)

The remote per-frame command already produces this. Do not guess field names;
these are exact.

### `herdr agent list --json`
```json
{ "result": { "agents": [
  { "agent": "claude", "agent_status": "idle",
    "cwd": "/Users/x/svn/dc-agent", "focused": false,
    "pane_id": "wF:p1", "tab_id": "wF:t1", "workspace_id": "wF",
    "terminal_id": "term_…", "terminal_title_stripped": "enable-ossindex-sonatype-auth" } ] } }
```
- `agent_status` ∈ `idle | working | blocked | done | unknown`.
  `done` = finished but the pane hasn't been looked at yet.
- Manually-started agents have **no `name`** field. Use
  `terminal_title_stripped` (the branch/task name) as the display title;
  fall back to `name` then `pane_id`.

### `herdr workspace list --json`
```json
{ "result": { "workspaces": [
  { "workspace_id": "wH", "label": "deploy", "number": 5,
    "agent_status": "working", "focused": true,
    "pane_count": 2, "tab_count": 2, "active_tab_id": "wH:t1" } ] } }
```
- Each workspace carries its **own rolled-up `agent_status`** — herdr computes
  it. Use it directly for the workspace status dot; don't recompute from agents.
- workspace ids are letters (`wB`,`wF`,`wH`), not integers. `number` is the
  human ordinal (1..N).

### `herdr worktree list --workspace <id> --json`
```json
{ "result": { "worktrees": [
  { "branch": "diff-service-redmine-jdk21", "path": "/Users/x/svn/deploy",
    "label": "deploy", "is_detached": false, "is_prunable": false,
    "is_linked_worktree": false, "open_workspace_id": "wH" } ] } }
```
- Worktrees belong to a workspace. The backend collects them per workspace (a
  loop over all `workspace_id`) and nests them under the workspace in the UI.
- Show `branch`, an `open · wX` marker when `open_workspace_id` is set, and a
  flag only when `is_detached` (red) or `is_prunable` (amber).

---

## Status colors (exact — from the approved mockup, do not change)

| status    | color     | priority |
|-----------|-----------|----------|
| blocked   | `#E24B4A` | 5 (top)  |
| working   | `#EF9F27` | 4        |
| done      | `#378ADD` | 3        |
| idle      | `#639922` | 2        |
| unknown   | `#888780` | 1        |

Health: connected `#639922`, degraded `#EF9F27`, unreachable `#888780`.
Rollup and host sorting use the priority column: hosts with a blocked agent
sort to the top; unreachable hosts sort to the bottom.

---

## Backend: what's DONE vs TODO

**DONE (in `backend/`, compiles clean on JDK 21 / Quarkus 3.15):**
- SshSource with the full remote frame command (workspace + agent + per-workspace
  worktree collection merged into one NDJSON frame), reconnect loop, tolerant
  line parsing.
- Registry with snapshot + broadcast, three health states.
- SourceManager (one virtual thread per enabled host).
- `GET /api/stream` (SSE), `GET /api/servers` (read-only list).
- application.yaml bootstrap host list, CORS for Vite dev.

**TODO — you implement:**
1. **Host CRUD** on `/api/servers`: `POST` (add), `PUT /{id}` (edit),
   `DELETE /{id}` (remove). Validate: `id` and `host` required; `pollInterval`
   and `reconnectDelay` positive integers; `id` unique. Return field-level
   errors in the interface's own voice (e.g. "Enter an ssh target"), not stack
   traces.
2. **Persistence layer**: the bootstrap `application.yaml` is read-only config.
   Layer a writable **state file** (JSON or TOML, e.g. `~/.config/herdr-watch/
   hosts.json`) on top: on startup, merge bootstrap hosts with the state file
   (state file wins); all CRUD writes go to the state file. Never write back to
   application.yaml.
3. **Hot (re)connect**: adding/enabling a host starts a new SshSource in a
   virtual thread immediately (no restart). Removing/disabling one stops its
   SshSource cleanly (interrupt the ssh process, stop the loop). Editing =
   stop + start with new params. Reflect the new connection state in the next
   SSE delta.
4. **Expose health in `/api/servers`** so the Settings table shows the live
   connection dot per host (the mockup expects this).

Keep the existing package layout and the Source abstraction. If you later want
realtime instead of polling, it should slot in as another Source impl behind
the same interface — but polling is the shipping default; don't build the
socket-forward path now.

---

## Frontend: build it

Stack: **Vite + React + TypeScript + shadcn/ui + Tailwind.** Match the approved
mockup in `design/` closely — it is the visual contract.

- Two views switched by a top nav: **Monitor** and **Settings** (see mockup).
- **Monitor**: host cards, each containing workspace groups, each containing
  agent rows and nested worktree rows. Health badge per host; unreachable card
  dimmed to ~60% opacity with last-known state grayed but visible. Top summary
  bar: total hosts + non-zero counts of down / blocked / working in their
  colors. Sort hosts by rollup priority, unreachable last.
- **Settings**: table of hosts (id, host, herdr-path, poll, reconnect, enabled
  toggle, live health dot, edit/remove actions with a confirm step). "Add host"
  opens a modal form with all fields and inline validation. Empty state invites
  adding the first host.
- **Data**: single `EventSource("/api/stream")`. On `snapshot` replace the host
  map; on `host_update` upsert one host. See `reference/dashboard-mock.jsx` for
  the exact hook and JSON shape. CRUD calls hit `/api/servers`.
- Typography: IBM Plex Sans (Cyrillic subset — users may see Russian labels)
  for prose/labels, JetBrains Mono for ids / branch names / paths / statuses.
- Dark theme, near-black `#0b0d10` page, cards `#14171d`. No gradients, no glow.
  Status colors must stay vivid against dark — that's the whole point.

Do NOT hardcode the mock data in the shipping frontend. The mockup embeds sample
data for preview only; the real app is driven entirely by SSE + REST.

---

## Runnable result & repo layout

Produce a single repo that runs with:

```bash
# terminal 1 — backend
cd backend && mvn quarkus:dev            # :8080, SSE at /api/stream

# terminal 2 — frontend
cd frontend && npm install && npm run dev   # :5173, proxies /api → :8080
```

Suggested final layout:
```
herdr-watch/
├── backend/    (the provided Quarkus app + your CRUD/persistence/hot-reconnect)
├── frontend/   (new Vite+React+shadcn app you build from the mockup)
└── README.md   (how to configure hosts, run both, remote requirements)
```

Remote host requirements to document in the README: `herdr` in PATH (or set
`herdr-path`), `jq` installed, passwordless SSH (key/agent, `BatchMode=yes`).

---

## Acceptance criteria

- `mvn quarkus:dev` starts; with a reachable host configured, `curl -N
  http://localhost:8080/api/stream` streams a snapshot then host_update frames.
- Adding a host in the Settings UI connects it live and it appears on Monitor
  within one poll interval; removing it disconnects and drops it — no restart.
- A host going unreachable dims its card and shows last-known state grayed; on
  recovery it re-populates.
- Agent/workspace/worktree fields render from the real schemas above; worktrees
  nest under workspaces; detached/prunable flags show.
- Monitor sorting: blocked-bearing hosts on top, unreachable at the bottom.
- Form validation blocks bad input with human-voiced field errors.
- Visual result matches the approved mockup (colors, type, layout, dark theme).

## Working style

- Start by running the provided backend as-is to confirm it builds, then add
  CRUD + persistence + hot reconnect. Then scaffold the frontend and wire SSE.
- Keep the Source abstraction and the one-ssh-connection-per-host model intact.
- Commit in logical steps (backend CRUD, persistence, hot reconnect, frontend
  scaffold, Monitor view, Settings view, wiring).

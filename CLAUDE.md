# CLAUDE.md

Guidance for AI agents working in this repo. Keep it accurate — update it when the
architecture or commands change. See `README.md` for user-facing docs and
`handoff/CLAUDE_CODE_INSTRUCTIONS.md` for the original spec (verified herdr schemas, colors).

## What this is

**herdr-watch** — a read-only dashboard that aggregates the live state of several
`herdr` servers (a terminal multiplexer for AI coding agents) into one monitor:
the local machine plus remote hosts over SSH. Ships as **one Quarkus app** — the
backend serves both the REST/SSE API and the built React frontend (via Quinoa) on
**:8080**.

## Architecture

```
LocalSource (bash -lc, no ssh) ─┐
SshSource   (1 ssh conn/host) ──┼─► Registry (in-memory, Mutiny broadcast) ─► SSE /api/stream ─► React UI
                                 └►                                            REST /api/servers (CRUD, hot-reconnect)
                                     Registry fires CDI FrameApplied ─► TelegramNotifier (blocked/done → Telegram)

statusline hook (Claude Code) ─► ~/.config/herdr-watch/claude-usage.json ─► ClaudeUsageReader (mtime poll)
                                                                              └─► Registry (claude_usage)
```

- **Sources** (`backend/.../source/`): `Source` interface + `AbstractHerdrSource`
  (the shared frame loop / parsing / health / clean stop, one long-lived process per
  host on a virtual thread). Two impls differ only in how the process is launched:
  `SshSource` (`ssh …`) and `LocalSource` (`bash -lc …`, current user, no ssh).
  `SourceManager` starts/stops/restarts one per host (hot (re)connect).
- **Registry** — single source of truth (in-memory `ConcurrentHashMap` + Mutiny
  `BroadcastProcessor`); emits `snapshot` / `host_update` / `host_remove`. Also fires
  a CDI `FrameApplied` event per CONNECTED frame → `TelegramNotifier` diffs agent
  statuses and notifies on transition to `blocked`/`done`.
- **Claude quota** (`usage/`): `ClaudeUsageReader` (`@Scheduled` mtime poll of the
  state file; parses only when mtime changed) → `Registry.updateClaudeUsage` →
  `claude_usage` SSE event. `ClaudeUsage` is the internal model (nullable windows,
  `NOT_CONFIGURED`/`OK`/`STALE`); `UsageSeverity` holds the 70/90 % bands shared with
  the UI and with `severityCode` in the Snapshot API. The file is written by
  `scripts/herdr-watch-statusline-hook.py`, a **pass-through** `statusLine` wrapper —
  it must never break or delay the operator's statusline (any failure is swallowed,
  stdin always forwarded, nothing on stdout).
- **HTTP** (`http/`): `StreamResource` (SSE; `onOverflow().drop()` so slow clients
  don't crash the broadcast), `ServersResource` (GET enriched config+health, POST/PUT/
  DELETE with field-level validation), `ClaudeUsageResource` (`GET /api/claude-usage`),
  `SnapshotResource` (Snapshot API v1, incl. `GET /api/v1/snapshot/usage`).
- **Config/persistence**: `HostsConfig` (`@ConfigMapping`, bootstrap hosts from
  `application.yaml`) merged with a writable state file `~/.config/herdr-watch/hosts.json`
  by `HostStore` (state wins; tombstones for removed bootstrap hosts). `TelegramConfig`
  (env-driven). `NativeReflectionConfig` registers records for Jackson in native.
- **Frontend** (`frontend/src/`): Vite + React + TS + Tailwind + shadcn/ui (Radix).
  One `EventSource("/api/stream")` (`hooks/useSse.ts`) drives Monitor & Compact;
  Settings uses REST. Views: `components/{monitor,compact,settings}/`, shared
  `Header`. Derivations/tokens in `lib/{types,theme,sort,api,prefs}.ts`.

## Commands (run from `backend/` unless noted)

- Dev (Quarkus + Vite HMR, all on :8080): `./mvnw quarkus:dev`
- Fast-jar: `./mvnw package` → `target/quarkus-app/quarkus-run.jar`
- Fat-jar: `./mvnw package -Puber` → `target/herdr-watch-*-runner.jar`
- Native (macOS, GraalVM **for JDK 21**): `JAVA_HOME=$GRAALVM GRAALVM_HOME=$GRAALVM ./mvnw package -Dnative`
- Frontend only: `cd frontend && npm run build` (tsc + vite) / `npm run typecheck`

**Always use `./mvnw`** — system Maven is 3.8.5, `quarkus:dev` needs ≥ 3.8.6 (wrapper is 3.9.9).
**Run only ONE server on :8080** at a time (dev / fast-jar / fat-jar / native).

## Conventions & invariants

- Keep the **Source abstraction** and **one connection per host** model. New source
  types slot in behind `Source`/`AbstractHerdrSource`.
- Frontend is driven **only by SSE + REST** — never hardcode fleet data.
- Status colors, priorities, and the `hex(color, alpha)` / `badgeStyle` helpers live
  in `frontend/src/lib/theme.ts` — single source; reuse them, don't duplicate hexes.
- `herdr` here (0.7.4) **emits JSON without `--json`** — the remote frame command must
  NOT pass `--json`.
- Claude quota **only advances while a Claude Code session is running**. With no session
  open the newest reading just ages — model that as `STALE` with a visible `capturedAt`,
  never as 0 %. An absent window is `null`/omitted, never zero. The Snapshot API forbids
  `null` (§3.4), so `SnapshotProjection.projectUsage` omits absent windows from the array.
- Secrets (Telegram token/chat id) come from **env only** (`TELEGRAM_*`), never the
  state file or UI.
- Native: any type serialized via a hand-rolled `ObjectMapper` or reachable only via
  `Object` (e.g. `StreamEvent.data`) must be added to `NativeReflectionConfig`
  (`@RegisterForReflection`). Use GraalVM for JDK 21 (matches Quarkus 3.15).

## Gotchas

- `ssh <host>` may log in as a **different unix user** than the interactive one, so an
  ssh host shows that account's herdr — that's why a `local` (no-ssh) source exists to
  read the current user's session.
- When stdout is redirected to a file it's **block-buffered**; post-startup logs may
  not appear until flush. For reliable log capture use
  `-Dquarkus.log.file.enable=true -Dquarkus.log.file.path=…`.
- A stale `java -jar …` and `./mvnw quarkus:dev` can **both** bind :8080 (wildcard vs
  localhost) and requests race between them — kill stragglers before testing.

## Key files

Backend: `usage/{ClaudeUsage,ClaudeUsageConfig,ClaudeUsageReader,UsageSeverity}.java`,
`source/{Source,AbstractHerdrSource,SshSource,LocalSource,SourceManager}.java`,
`Registry.java`, `FrameApplied.java`, `HostStore.java`, `HostsConfig.java`,
`http/{StreamResource,ServersResource,ClaudeUsageResource,SnapshotResource}.java`,
`model/{Model,HostDef}.java`,
`TelegramConfig.java`, `notify/TelegramNotifier.java`, `NativeReflectionConfig.java`,
`src/main/resources/application.yaml`, `pom.xml` (profiles `uber`, `native`;
`quarkus-scheduler` drives the quota poll).

Hook: `scripts/herdr-watch-statusline-hook.py` + `scripts/test_herdr_watch_statusline_hook.py`
(`python3 scripts/test_herdr_watch_statusline_hook.py`).

Frontend: `App.tsx`, `components/Header.tsx`, `components/UsageGauge.tsx`,
`components/{monitor,compact,settings}/*`, `hooks/{useSse,useLocalHosts}.ts`,
`lib/{types,theme,sort,api,prefs}.ts`, `tailwind.config.js`,
`vite.config.ts` (proxy `/api` → :8080 for standalone Vite).

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

herdr-watch-statusline (Rust; IS the Claude Code statusLine command)
   stdin payload ─► rendered line ─► terminal
              └──► ~/.config/herdr-watch/claude-usage.json ─► ClaudeUsageReader (mtime poll)
                                                                              └─► Registry (claude_usage)
Claude Code credential (read-only) ─► ClaudeUsagePullReader ─► api.anthropic.com/api/oauth/usage
                                       (optional, off by default) └─► Registry (claude_usage)
```

- **Sources** (`backend/.../source/`): `Source` interface + `AbstractHerdrSource`
  (the shared frame loop / parsing / health / clean stop, one long-lived process per
  host on a virtual thread). Two impls differ only in how the process is launched:
  `SshSource` (`ssh …`) and `LocalSource` (`bash -lc …`, current user, no ssh).
  `SourceManager` starts/stops/restarts one per host (hot (re)connect).
- **Registry** — single source of truth (in-memory `ConcurrentHashMap` + Mutiny
  `BroadcastProcessor`); emits `snapshot` / `host_update` / `host_remove`. For the
  quota it keeps the latest reading **per source** and publishes the most recently
  *observed* one — not «pull wins»: pull polls on an interval, so a statusline
  reading can legitimately be newer. Also fires
  a CDI `FrameApplied` event per CONNECTED frame → `TelegramNotifier` diffs agent
  statuses and notifies on transition to `blocked`/`done`.
- **Claude quota** (`usage/`): two sources, one snapshot. **push** —
  `ClaudeUsageReader` (`@Scheduled` mtime poll of the state file; parses only when
  mtime changed). **pull** (`usage/pull/`, optional, `source: push|pull|auto`,
  default `push`) — `ClaudeUsagePullReader` polls `GET /api/oauth/usage` with the
  credential Claude Code already holds (`CredentialSource` chain: keychain → file),
  `PollPolicy` decides when the next attempt is allowed, `UsageResponseMapper`
  normalises the body. Both feed `Registry.updateClaudeUsage` →
  `claude_usage` SSE event. `ClaudeUsage` is the internal model (nullable windows,
  `NOT_CONFIGURED`/`OK`/`STALE`); `UsageSeverity` holds the 70/90 % bands shared with
  the UI and with `severityCode` in the Snapshot API. The file is written by
  **`statusline-rust/`** — `herdr-watch-statusline`, the binary the operator configures
  *as* their `statusLine` command: it renders the line and records the quota from the
  same payload. It must never fail visibly (any failure still leaves a line on stdout,
  exit is always 0, stderr is always empty) and it reads nothing but stdin — no network,
  no credential, no subprocess.
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
- Status line (from repo root): `cargo test --manifest-path statusline-rust/Cargo.toml`,
  `cargo fmt --check --manifest-path statusline-rust/Cargo.toml`,
  `cargo clippy --manifest-path statusline-rust/Cargo.toml --all-targets -- -D warnings`.
  Install: `cargo install --path statusline-rust`. All three checks run in CI as a
  separate job, so a Rust failure cannot block `./mvnw verify`.

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
  never as 0 %.
- Статус-строка перезаписывает state-файл **только когда цифры изменились**, так что
  `capturedAt` означает «когда показания сдвинулись», не «когда команду вызвали». Не
  «чинить» это: с `statusLine.refreshInterval` команду зовут по таймеру без API-вызова, и
  штамповка времени на каждый запуск выдавала бы старые цифры за свежие — индикатор
  устаревания умирает. Не двигается и **mtime**: по нему читатель решает, перечитывать ли.
- Округление процентов асимметрично намеренно: **в запись — half-up** (Java-читатель
  переокругляет через `Percents.toWhole`), **в строку — half-even** (байтовое совпадение
  с питоновским рендерером, который бинарник заменил). См. `statusline-rust/src/round.rs`.
- State-файл делят сессии Claude Code **разных версий**, и отставшая портит запись.
  Показание принимается, только если в нём есть пригодное `five_hour` И оно не
  откатывается назад: окно с `resets_at` раньше записанного (уже сброшенное) и падение процента внутри
  ОДНОГО окна отвергаются. Опора — свойства данных, а не версии клиента: утилизация внутри
  окна не убывает, время сброса движется только вперёд. An absent window is `null`/omitted, never zero. The Snapshot API forbids
  `null` (§3.4), so `SnapshotProjection.projectUsage` omits absent windows from the array.
- Secrets (Telegram token/chat id) come from **env only** (`TELEGRAM_*`), never the
  state file or UI.
- The pull source reads the Claude Code credential and **never** writes, refreshes or
  deletes it. Two traps worth never repeating:
  **(1)** the keychain service `Claude Code-credentials` holds **several** items —
  select by evidence (unexpired `expiresAt`, `user:profile` in `scopes`), never by
  store ordering; looking up by service alone returns the expired one and produced a
  wrong conclusion that stood for a whole change cycle. `expiresAt` is in
  **milliseconds**.
  **(2)** the backoff cap **must exceed the endpoint's penalty window** (~1300 s
  observed) — a comfortable-looking 5 m cap means the source retries inside the
  penalty forever and never recovers.
- `impersonate-claude-cli` **fails closed**: with `source: pull|auto` and the flag
  `false` the pull source does not start and issues no request. Never «just send our
  own User-Agent» — that lands in a much stricter rate-limit bucket.
- `source` decides what is **published**, not only who polls: under `source: pull`
  the statusline reader stays silent (spec: a recorded statusline reading is not
  published when only the account API is selected).
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

Backend: `usage/{ClaudeUsage,ClaudeUsageConfig,ClaudeUsageReader,UsageSeverity,UsageSource}.java`,
`usage/pull/{ClaudeUsagePullReader,ClaudeUsageApiClient,PollPolicy,UsageResponseMapper,
ClaudeCredential,KeychainCredentialSource,FileCredentialSource,ChainedCredentialSource,
ClaudeCliVersion,PullOutcome}.java`,
`source/{Source,AbstractHerdrSource,SshSource,LocalSource,SourceManager}.java`,
`Registry.java`, `FrameApplied.java`, `HostStore.java`, `HostsConfig.java`,
`http/{StreamResource,ServersResource,ClaudeUsageResource,SnapshotResource}.java`,
`model/{Model,HostDef}.java`,
`TelegramConfig.java`, `notify/TelegramNotifier.java`, `NativeReflectionConfig.java`,
`src/main/resources/application.yaml`, `pom.xml` (profiles `uber`, `native`;
`quarkus-scheduler` drives the quota poll).

Status line (`statusline-rust/`): `src/{main,args,json,round,capture,store,fmt,render,transcript}.rs`,
`tests/{capture,render,concurrency}.rs` + `tests/data/*.{json,expected}` (golden-корпус,
замороженный сверкой с питоновским оригиналом). Контракт — `openspec/specs/claude-usage/spec.md`,
решения D1…D10 — `openspec/changes/render-statusline-directly/design.md`.

Frontend: `App.tsx`, `components/Header.tsx`, `components/UsageGauge.tsx`,
`components/{monitor,compact,settings}/*`, `hooks/{useSse,useLocalHosts}.ts`,
`lib/{types,theme,sort,api,prefs}.ts`, `tailwind.config.js`,
`vite.config.ts` (proxy `/api` → :8080 for standalone Vite).

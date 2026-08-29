# Design: Claude quota gauge (push model)

## Context

See `proposal.md — Why` for motivation and `specs/claude-usage/spec.md` for the
behaviour contract.

Constraints that shape the approach:

- **Quarkus 3.15.1**, single app on :8080, Java 21, must keep building as a
  GraalVM native image. No scheduler extension yet.
- `Registry` is the single source of truth and owns the Mutiny
  `BroadcastProcessor`. `StreamResource` merges it with a 15s `ping` heartbeat
  under `onOverflow().drop()`.
- `docs/api/openapi.yaml` is generated at build time and **checked in CI for
  drift**; tests use `swagger-request-validator-restassured`.
- The Snapshot API contract (`docs/api/herdr-watch-snapshot-protocol.md`)
  forbids `null` (§3.4), freezes the `compact` / `status` profile composition
  (§7), and treats new fields and new endpoints as compatible additions (§7).
- Anything reaching a client via `StreamEvent.data` (typed `Object`) must be in
  `NativeReflectionConfig`.

### The statusline payload

Claude Code invokes the `statusLine` command from `settings.json` with a JSON
document on stdin. Relevant fragment, verified against the 2.1.247 binary:

```json
"rate_limits": {
  "five_hour": { "used_percentage": 0-100, "resets_at": <epoch seconds> },
  "seven_day": { "used_percentage": 0-100, "resets_at": <epoch seconds> }
}
```

Facts that shape the design:

1. **The key is absent until limits have been observed.** The binary emits it as
   `...(O.five_hour || O.seven_day) && { rate_limits: O }`, so a fresh session
   before its first API response has no `rate_limits` at all. Absence is normal,
   not an error.
2. **`used_percentage` is on a 0–100 scale**, not a fraction — unlike the
   `utilization` field on the underlying API. It is **not** always an integer,
   though: it is computed as a fraction × 100, so any value that is not whole
   arrives with floating-point noise (observed live: `7.000000000000001`, while
   `seven_day` in the same payload was a plain `34`). A consumer that type-checks
   for `int` silently drops the window — that is exactly how the 5-hour bar came
   and went. Accept both, and round.
3. **Invocation is event-driven**, on session state changes. `settings.json`
   additionally accepts `statusLine.refreshInterval`, documented as "Re-run the
   status line command every N seconds in addition to event-driven updates"
   (minimum 1). The exact upper clamp and debounce were not determined and are
   not relied upon.
4. **Data only advances while a session runs.** With no Claude Code open, the
   newest record simply ages. This is the defining property of the whole design.

## Goals / Non-Goals

**Goals**

- No credential, no outbound network call, no OAuth refresh anywhere in
  herdr-watch.
- Never degrade the operator's statusline — it is on an interactive path.
- Represent age honestly: a number without its capture time is misleading here.
- Zero impact on host collection.

**Non-Goals**

- No persistence or history; in-memory like the rest of `Registry`.
- No per-agent, per-workspace or per-model attribution.
- No quota alerting; `TelegramNotifier` is untouched.
- No remote hosts in this change (each would need its own hook installed).

## Decisions

### D1. A pass-through wrapper, not an edit to the operator's script

**Decision:** ship a hook that is invoked *as* the `statusLine` command and
execs the operator's real command, forwarding stdin:

```json
"statusLine": {
  "type": "command",
  "command": "python3 ~/.claude/herdr-watch-hook.py python3 ~/.claude/statusline.py"
}
```

**Why not edit the operator's own statusline script:** it is their file, often
personal and long-lived (the reference machine's is 452 lines). A wrapper leaves
it untouched, survives their edits, and is removable by reverting one settings
line. It also keeps the capture logic in this repo, where it is versioned and
testable.

**Alternative considered:** a Claude Code *hook* (`settings.json → hooks`). The
hook events carry tool lifecycle data; `rate_limits` is delivered only to the
statusline. Not available.

### D2. The hook must be transparent, and that is a hard requirement

A statusline runs on every session state change and its output is on screen
constantly. A hook that errors, prints, or stalls is worse than no feature.

Therefore: capture is wrapped in a catch-all; any failure is swallowed; stdin is
forwarded regardless; the exit status is the wrapped command's. The hook writes
nothing to stdout itself. Reading stdin fully before forwarding is required (it
must parse it), so the wrapped command receives it as a single buffer.

This is expressed in the spec as its own requirement rather than left to
implementation, because it is the difference between an unobtrusive feature and a
daily irritation.

### D3. Atomic write to herdr-watch's own config directory

**Location:** `~/.config/herdr-watch/claude-usage.json`, beside the existing
`hosts.json` that `HostStore` owns. It is herdr-watch's state, not Claude Code's,
so it does not belong in `~/.claude/`.

**Atomicity:** write to a temporary file in the same directory, then `rename()`.
On POSIX that is atomic, so a concurrent reader sees either the old or the new
file, never a partial one.

~~This also makes concurrent sessions safe — last writer wins, and since every
session on one account reports the same account-level quota, that is benign rather
than a lost update.~~

**Correction (2026-08-28): that second sentence was wrong, and it cost a bug.**
Concurrent sessions are *not* necessarily the same **version** of Claude Code, and
different versions do not report interchangeable data. Observed live on the
reference machine — six sessions, one `refreshInterval` tick, one second apart:

| session | version | `rate_limits` |
|---|---|---|
| `0fdc87bd` | 2.1.243 | `seven_day` 7.0 — **no `five_hour`** |
| `1b36bde1` | 2.1.245 | `seven_day` 20 — **no `five_hour`** |
| `0ef349ff` | 2.1.247 | `seven_day` 26 — **no `five_hour`** |
| current | 2.1.250 | `five_hour` + `seven_day` 34 (matches the account API) |

The older shape carries no five-hour window at all, and its `seven_day` agrees
neither with the account figure nor between sessions. Last-writer-wins therefore let
a stale client overwrite a good record on every tick, and the 5-hour bar vanished
every five minutes.

Atomicity was never the issue; **comparability of writers** was. The record is
account-scoped state in one shared file, so the hook must decide *whether a writer is
worth listening to*, not merely write safely. It now accepts a reading only when a
usable `five_hour` is present and leaves the record untouched otherwise — the window
the old shape never carries, used as the marker of a complete reading.

`statusLine.refreshInterval` made the damage visible rather than causing it: before
it, only event-driven fires happened, so the session being typed in usually won.

**Record shape** — deliberately close to the payload, so the hook stays trivial:

```json
{ "capturedAt": 1787797108, "five_hour": {...}, "seven_day": {...} }
```

Windows absent from the payload are absent here too (spec: absence is never zero).

### D4. Read by mtime poll, not a filesystem watcher

**Decision:** `quarkus-scheduler` runs a cheap periodic check; if the file's
mtime is unchanged, do nothing. Parse and publish only on change.

**Why not `WatchService`:** it needs a thread and lifecycle management, behaves
inconsistently on macOS (polling under the hood anyway), and the write pattern is
`rename()`, which watchers report unevenly. An mtime check is a `stat` — cheap
enough to run every few seconds, with no lifecycle to get wrong.

This replaces the HTTP client and poller of the rejected pull design; the
`quarkus-rest-client-jackson` dependency is **no longer needed**.

### D5. Snapshot lives in `Registry`, alongside the host map

The snapshot is dashboard state, and `Registry` owns dashboard state and the
broadcast. `Registry` gains a `volatile ClaudeUsage` field and
`updateClaudeUsage(...)`, emitting `StreamEvent("claude_usage", snapshot)` on the
same bus — so the existing `onOverflow().drop()` covers it and `sequence`
increments as for any mutation. Publishing only on change (D4) keeps the stream
quiet.

The initial `snapshot` handshake keeps its `List<HostState>` shape; changing it
would break Snapshot API consumers.

### D6. Explicit state enum, absence modelled as null internally

```
ClaudeUsage {
  state:      NOT_CONFIGURED | OK | STALE
  capturedAt: Long | null
  error:      String | null
  windows: {
    fiveHour: null | { usedPercent: int, resetsAt: long },
    sevenDay: null | { usedPercent: int, resetsAt: long }
  }
}
```

`NOT_CONFIGURED` (no file has ever appeared) is distinct from `STALE` (a record
exists but is old or unreadable) — the UI must tell them apart, and a
nullable-field encoding pushes that inference onto every client. Each window is
nullable as a whole so "not reported" cannot be confused with "0%".

There is no `ERROR` state: an unreadable file with a previous snapshot is `STALE`
with a reason, and with no previous snapshot it is `NOT_CONFIGURED`. A missing
file is the normal pre-install condition, not a failure.

### D7. A separate Snapshot endpoint, not a field on `/agents`

Embedded clients (§1 of the Snapshot contract) cannot consume SSE and need a
polled surface. Add `GET /api/v1/snapshot/usage`, modelled on
`/api/v1/snapshot/time`.

**Rejected alternatives:**

- *Extend `compact` / `status`.* Forbidden — §7 lists changing an existing
  profile's composition as incompatible, and §3.5 fixes composition precisely so
  clients can parse without key checks.
- *Top-level field on `/agents`.* Legal under §7, but top-level fields are
  profile-independent (§3.5), so it would ship quota bytes to every `status`
  consumer — a profile that exists to be ~0.7 KB for LED panels.
- *A new `view` profile.* `view` selects **agent record** fields; quota is not
  agent data.

### D8. Snapshot representation differs from the internal model

The Snapshot contract forbids `null` (§3.4), which collides with D6:

```
GET /api/v1/snapshot/usage
{
  "protocolVersion": 1,
  "state": "OK",             // OK | STALE | NOT_CONFIGURED
  "severityCode": 2,         // 0 unknown/not-configured, 1 ok, 2 warning, 3 critical, 4 exhausted
  "capturedAt": 1787797108,  // 0 when never captured
  "windows": [
    { "type": "five_hour", "usedPercent": 25, "resetsAt": 1787800000 },
    { "type": "seven_day", "usedPercent": 24, "resetsAt": 1788300000 }
  ]
}
```

- **Absence is omission** — a window not recorded is simply not in the array. An
  empty array covers `NOT_CONFIGURED` uniformly. No nulls, no fabricated zeros.
- **`severityCode` mirrors the `statusCode` idiom** (§3.5: "индикаторы без
  текста: светодиодная панель"). Those clients switch on one integer.
- **`ETag` as `"usage-<capturedAt>"`**, per §3.10's rule that the validator covers
  everything affecting the body. Worth having: a device polling faster than the
  data changes gets bodyless `304`s, which is the common case here since the data
  only moves while a session runs.
- Additive, so `protocolVersion` stays `1`.

### D9. Frontend surface

`useSse.ts` gains a `claude_usage` case writing to separate state — not the host
map, since this is not a host property. `HostCard` renders the gauge only when
`server.local === true` and state is `OK`/`STALE`, with an explicit account-scoped
label, each window's reset time, and the capture time.

Severity bands reuse `hex(color, alpha)` / `badgeStyle` from `lib/theme.ts`; no
new hex literals. The same thresholds drive `severityCode` (D8) — derive both
from one constant so they cannot drift.

## Rejected: the pull design

Recorded so it is not re-derived. The original design polled
`GET /api/oauth/usage` directly. Abandoned after the following was established by
probe, not inference:

| | |
|---|---|
| Base host | `https://api.anthropic.com` (`claude.ai` serves a Cloudflare page) |
| `claude setup-token` | `403 "does not meet scope requirement user:profile"`; mints `user:inference` only, no `--scope` flag |
| Keychain `Claude Code-credentials` | **two** items under one service — see correction below |
| `~/.claude/daemon-auth-status.json` | `auth_required` (describes the dead item, not current auth) |

**Correction (2026-08-28).** The keychain rows above were a measurement error, and
the conclusion drawn from them was wrong. The service `Claude Code-credentials`
holds **two** generic-password items distinguished only by account:

| acct | state |
|---|---|
| `<username>` | live — rewritten by every `claude auth login`, carries `user:profile` |
| `no` | a fossil; both tokens expired 19–20 July |

`security find-generic-password -s "Claude Code-credentials" -w` **without `-a`**
returns whichever it finds first, and on the reference machine that is consistently
the fossil. So the original probe read a 39-day-dead token, concluded the credential
was unusable, and reported that `claude auth login` "did not rewrite the item" — it
did, to the other one. A `SecItemCopyMatching` with `kSecMatchLimitOne` and no
account attribute hits the same trap; two of the surveyed macOS trackers do exactly
that and would misdiagnose this machine identically.

Re-probed with the **live** item: `GET https://api.anthropic.com/api/oauth/usage`
returns **HTTP 200**, with `five_hour`, `seven_day`, and a `limits[]` array carrying
model-scoped weekly entries (including `Fable`). The `claude-cli/<version> (external,
cli)` User-Agent matters — without it the request lands in a much stricter
rate-limit bucket.

So the pull design is **not** blocked by credential availability, as this section
previously claimed. It remains not-chosen for the reasons that still hold on their
own merits: it needs a credential at all, it depends on an undocumented and
unversioned endpoint, and the push design needs neither. That is a trade-off, not an
impossibility — anyone revisiting this should start from the corrected facts.

Even had it worked, the pull design stacked three unversioned dependencies: an
undocumented endpoint, an undocumented credential-store layout, and a refresh-token
rotation race against Claude Code. The push design has one dependency, on a
documented extension point.

## Risks / Trade-offs

- **Data only advances while Claude Code runs** → Inherent, and arguably
  acceptable for a dashboard about agents. Mitigated by publishing `capturedAt`
  and a `STALE` state rather than pretending freshness. `statusLine.refreshInterval`
  can tighten updates while a session is open, but cannot help when none is.
- **The statusline payload is a contract that can change** → It is a documented
  extension point, but the `rate_limits` field within it is not separately
  versioned. A shape change means the hook stops recording; the reader then ages
  into `STALE`, which is visible rather than silent.
- **Requires an operator install step** → One line in `settings.json`. Documented
  in README; the wrapper form means it is one line to add and one to remove.
- **Hook runs on an interactive path** → D2 makes transparency a spec-level
  requirement, not an implementation nicety.
- **Multiple concurrent sessions write the same file** → Atomic rename plus
  last-writer-wins; all sessions on one account report the same quota, so there is
  no lost update in any meaningful sense.
- **OpenAPI / protocol drift breaks CI** → Regenerating `openapi.yaml` and
  documenting the new endpoint are explicit tasks.
- **Native image** → `quarkus-scheduler` supports native, but the new records
  travel via `StreamEvent.data` and must be registered in
  `NativeReflectionConfig`. A native build is an explicit task.

## Migration Plan

Additive and reversible; no data migration.

1. Ship with the reader enabled but harmless: with no state file, the feature
   reports `NOT_CONFIGURED` and renders nothing.
2. Install the hook in `settings.json` on the monitored host; confirm the gauge
   appears and matches the statusline.
3. **Rollback:** revert the `settings.json` line and delete the state file. Nothing
   is persisted beyond that file; a code revert needs no schema or state change.

## Open Questions

- **Severity thresholds** — placeheld at 70% / 90%, shared between UI and
  `severityCode`. Tunable without touching spec or tasks.
- **Staleness threshold** — the age at which a record is marked `STALE`. Depends
  on real session cadence; a config value, not a design change.
- **Whether the hook should also record `cost` and `context_window`** — confirmed
  present in the captured payload:
  `cost: { total_cost_usd, total_duration_ms, total_api_duration_ms, total_lines_added, total_lines_removed }`
  and `context_window: { total_input_tokens, total_output_tokens, context_window_size, current_usage: {...cache breakdown} }`,
  alongside `model`, `effort`, `fast_mode`, `session_id` and `workspace`.
  Out of scope here; noted because the capture point makes it nearly free later,
  and because `cost` is per-session — it is **not** the org spend that the
  deferred `add-claude-cost-breakdown` would report, and must not be conflated
  with it.

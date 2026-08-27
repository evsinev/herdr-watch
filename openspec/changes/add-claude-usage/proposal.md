# Add Claude quota gauge (5-hour / weekly window)

## Why

herdr-watch answers "what are my agents doing?" but not "will they hit the wall
mid-task?". When a Claude subscription's 5-hour session window runs out, agents
stall — and the only way to see it coming today is to read the statusline inside
a Claude Code session. Putting that headroom next to the agents themselves turns
an unexplained stall into a predictable event.

## What Changes

**Data reaches herdr-watch by push, not by pull.** Claude Code already hands the
quota to any configured `statusLine` command on stdin:

```json
"rate_limits": {
  "five_hour": { "used_percentage": 0-100, "resets_at": <epoch seconds> },
  "seven_day": { "used_percentage": 0-100, "resets_at": <epoch seconds> }
}
```

- **New pass-through statusline hook** shipped by this repo: it reads that stdin
  payload, atomically writes the quota to a small state file, then forwards stdin
  unchanged to the operator's real statusline command. Adding it must never break
  or delay the statusline.
- **New backend reader**: herdr-watch watches that state file and publishes a
  `ClaudeUsage` snapshot into the existing `Registry` broadcast when it changes.
- **New SSE event** `claude_usage` on `/api/stream`, plus `GET /api/claude-usage`.
- **New Snapshot API endpoint** `GET /api/v1/snapshot/usage` for embedded clients
  (ESP32 and similar) that cannot hold an SSE connection — modelled on the
  existing `/api/v1/snapshot/time`. A **compatible** addition under §7 of the
  Snapshot protocol: no `protocolVersion` bump, frozen `compact` / `status`
  profiles untouched.
- **Frontend**: a compact quota gauge inline on the **local** host's `HostCard`
  and its Compact-view equivalent, coloured from existing `lib/theme.ts` tokens.
- No breaking changes; the new SSE event type is ignored by clients that don't
  know it, as the `ping` heartbeat already established.

### Why push, and not the API we first designed against

The pull design is recorded in full in design.md (D1–D3, "Rejected: the pull
design"). It was abandoned on evidence, not preference:

| | |
|---|---|
| `GET /api/oauth/usage` on `api.anthropic.com` | works, but is undocumented and unversioned |
| `claude setup-token` | `403 "does not meet scope requirement user:profile"` — inference-only, no `--scope` flag |
| Keychain `Claude Code-credentials` | on the test machine: access token expired 38.5 d, refresh token 37.9 d |
| `claude auth login` (reported success) | did **not** rewrite that item; a full Keychain sweep found no other `*-credentials` entry |

No credential source was found that an external process could read and use. The
statusline route removes the credential question entirely — Claude Code holds its
own token and hands us only the derived numbers.

It also trades an undocumented HTTP endpoint for `statusLine`, a **supported,
documented extension point** configured in `settings.json`.

## What this change deliberately does not do

- **No Anthropic Admin API.** `usage_report/messages` and `cost_report` describe
  the organization's API-key traffic and never include subscription sessions;
  `usage_report/claude_code` is a per-user daily rollup delayed up to an hour.
  None expresses "37% of the 5-hour window, resets at 04:59". Model/cost
  breakdown remains a separate future change (`add-claude-cost-breakdown`).
- **No Fable-specific usage.** The statusline payload carries `five_hour` and
  `seven_day` only. A richer per-model view exists upstream (entries with
  `kind: "weekly_scoped"` and `scope.model.display_name`), but it is not in this
  payload, and whether it ever names Fable is unverified. If it appears, that is a
  follow-up, not a silent scope expansion.
- **Local host only.** Each host would need its own hook installed; remote hosts
  are deliberately out of scope and can be added later.
- **No quota history.** In-memory only, like the rest of `Registry`.

## Capabilities

### New Capabilities

- `claude-usage`: capturing the Claude subscription quota that Claude Code emits
  to its statusline hook, publishing the 5-hour and weekly window utilization
  with reset times to dashboard clients over SSE, REST and the Snapshot API, and
  degrading visibly when the data goes stale.

### Modified Capabilities

<!-- None. First capability in openspec/specs/; sources, registry frames and host
     CRUD are unchanged. -->

## Impact

**New**
- A pass-through statusline hook script shipped in this repo, plus operator
  install instructions.
- `usage/` backend package: state-file reader, change detection, snapshot record.

**Backend (touched)**
- `Registry` — holds the latest snapshot, emits `claude_usage`.
- `http/StreamResource` — rides the existing broadcast; the initial `snapshot`
  handshake is unchanged.
- `http/SnapshotResource` + `snapshot/` — new `usage` endpoint and record, obeying
  the Snapshot contract's no-`null` rule (§3.4) and its integer-code idiom (§3.5).
- `NativeReflectionConfig` — the snapshot travels via `StreamEvent.data` (`Object`).
- `application.yaml`, `pom.xml` — state-file path, poll interval; one new
  extension (`quarkus-scheduler`). **No HTTP client** is needed any more.
- `docs/api/herdr-watch-snapshot-protocol.md`, `docs/api/openapi.yaml`.

**Frontend (touched)**
- `lib/types.ts`, `hooks/useSse.ts`, `components/monitor/HostCard.tsx`,
  `components/compact/CompactView.tsx`; colours reuse `lib/theme.ts`.

**Operational**
- No outbound network dependency and no credential — a significant reduction in
  attack surface and failure modes versus the pull design.
- Requires an operator install step in `settings.json` on the monitored host.
- Data only advances while a Claude Code session is running; the design must
  represent that as staleness rather than as zero.

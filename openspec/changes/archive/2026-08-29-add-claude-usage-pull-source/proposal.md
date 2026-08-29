## Why

The shipped quota gauge reads what Claude Code hands its statusline. That has one
structural hole: **the figures only advance while a Claude Code session is running on
this machine.** Quota spent by another machine, by a teammate's session on the same
account, or during any period with no session open, is invisible — the record simply
ages into `STALE`. The gauge is least informative exactly when you have been away and
most want to know whether there is headroom.

`GET https://api.anthropic.com/api/oauth/usage` closes that hole. It was previously
recorded as unreachable; that was a measurement error, corrected in
`add-claude-usage/design.md`. Verified on the reference machine (2026-08-28) with the
live Claude Code OAuth token: **HTTP 200**, returning `five_hour` 7 %, `seven_day`
34 %, and a `limits[]` array carrying model-scoped weekly windows including
`weekly_scoped [Fable] 14 %`.

That last part is the second reason. **Per-model weekly windows exist only here** —
the statusline payload carries `five_hour` and `seven_day` and nothing else. The
original change deferred Fable as unverified; it is now verified, and this is the only
route to it.

## What Changes

- **New optional pull source.** A poller reads the endpoint and publishes into the
  same `Registry` snapshot the push source already feeds.
- **The source is chosen in configuration**, not inferred: `push` (today's behaviour
  and the **default**), `pull`, or `auto` (both run, freshest reading wins). The
  zero-credential property of the shipped design stays the default posture — pull
  needs a credential, so nobody gets it by accident.
- **Read-only credential access.** The Claude Code OAuth token is read, never written
  or refreshed — Claude Code owns rotation. On macOS from the Keychain service
  `Claude Code-credentials`, on headless Linux from `~/.claude/.credentials.json`.
  **The Keychain service can hold more than one item**; selection must be by account,
  not by service alone. Reading by service alone is what produced the original wrong
  conclusion, and two of the surveyed third-party trackers have the same defect.
- **Client fingerprint behind an explicit opt-in.** The endpoint keys its rate-limit
  bucket on the client; without the Claude Code User-Agent requests land in a much
  stricter bucket (`anthropics/claude-code#30930`). Sending it means presenting as a
  first-party client, so it is a separate configuration flag the operator must set
  knowingly. Without it the pull source refuses to start rather than running crippled.
- **Rate-limit discipline as a first-class requirement.** The endpoint 429s hard per
  token, and retrying inside the penalty window re-trips it. The server's `Retry-After`
  must always beat our own schedule, and the backoff ceiling must exceed the observed
  penalty or the source can never recover.
- **Per-model weekly windows** (Fable, Opus, Sonnet, Design) flow through the model,
  the SSE event, the REST endpoint, the Snapshot API and the gauge. Additive
  everywhere; the Snapshot API's frozen `compact` / `status` profiles are untouched
  and `protocolVersion` stays `1`.
- **Two sources, one snapshot.** Under `auto` both run, each reading carries which
  source observed it, and the fresher observation wins — so the two degrade
  independently and neither disables the other.
- **The gauge says where its numbers came from.** Which source produced the displayed
  figures is visible in the UI, alongside the capture time that is already there. A
  number whose provenance is invisible is exactly the kind of thing this feature has
  already been bitten by: under `auto` the two sources have different freshness and
  different failure modes, and "34 %, 3 m ago" means something different depending on
  which one said it. The provenance travels the whole way out — SSE, REST and the
  Snapshot API — so embedded clients can distinguish them too.
- No breaking changes. With the pull source disabled — the default — behaviour is
  byte-identical to today.

## Capabilities

### New Capabilities

- `claude-usage-pull`: obtaining the Claude subscription quota directly from the
  account API as an optional second source — reading the existing Claude Code
  credential without modifying it, obeying the endpoint's rate-limit discipline,
  requiring informed opt-in for the client fingerprint, reconciling with the
  statusline source, and publishing the per-model weekly windows that only this route
  exposes.

### Modified Capabilities

<!-- None expressible yet. The `claude-usage` capability is implemented but its spec
     still lives in the sibling `add-claude-usage` change and has not been synced into
     `openspec/specs/`, so there is no path to write a delta against. The per-model
     windows and the source-provenance field extend that capability; when
     `add-claude-usage` is archived, they belong there. Recorded as a dependency
     rather than a silent omission. -->

## Impact

**Depends on** `add-claude-usage` (implemented, not archived). This change builds on
its `usage/` package, `ClaudeUsage` model and `Registry` integration.

**New**
- `usage/pull/` backend package: credential source (Keychain + file), HTTP client,
  poll policy with `Retry-After`-aware backoff, response mapping.
- Configuration block: source selection (`push` | `pull` | `auto`), the fingerprint
  opt-in, poll intervals, backoff bounds, credential path override.

**Backend (touched)**
- `ClaudeUsage` — per-model windows, and provenance on each reading.
- `Registry` — reconciling two sources rather than accepting one.
- `usage/ClaudeUsageReader` — becomes one of two sources rather than the only one.
- `snapshot/` + `http/SnapshotResource` — per-model entries in the usage response.
- `NativeReflectionConfig`, `application.yaml`, `pom.xml` (an HTTP client returns).
- `docs/api/herdr-watch-snapshot-protocol.md`, `docs/api/openapi.yaml`.

**Frontend (touched)**
- `lib/types.ts`, `components/UsageGauge.tsx` — per-model rows, and a source label so
  the operator can see whether a figure came from the statusline or the account API.

**Operational**
- Reintroduces an outbound network dependency and a credential on the monitored host,
  both opt-in. The endpoint is undocumented and unversioned; a shape change must
  degrade to the push source rather than to a broken gauge.
- Local host only, as with push. Remote hosts stay out of scope.

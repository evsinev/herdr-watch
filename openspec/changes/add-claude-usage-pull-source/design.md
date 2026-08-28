# Design: pull source for the Claude quota

## Context

See `proposal.md — Why` and `specs/claude-usage-pull/spec.md`.

Builds directly on `add-claude-usage` (implemented, not archived): the `usage/`
package, the `ClaudeUsage` model, `Registry.updateClaudeUsage(...)`, the
`claude_usage` SSE event, `GET /api/claude-usage`, and `GET /api/v1/snapshot/usage`
all exist and are reused.

### What was verified, and what only observed

Established on the reference machine, 2026-08-28, by probe:

| | |
|---|---|
| `GET https://api.anthropic.com/api/oauth/usage` | **HTTP 200** with the live Claude Code bearer |
| Body, top level | `five_hour`, `seven_day` (`utilization` as a **float**, `resets_at` as an **ISO-8601 string**), plus `limits`, `spend`, `extra_usage` and several opaque keys |
| `limits[]` entries | `{kind, percent, resets_at, scope}`; `kind` seen as `session`, `weekly_all`, `weekly_scoped`; `scope.model.display_name` seen as `Fable` |
| Values at probe time | `five_hour` 7 %, `seven_day` 34 %, `weekly_scoped [Fable]` 14 % |
| Keychain `Claude Code-credentials` | **two** generic-password items differing only by account; one live, one expired since July |
| Live credential shape | `{"claudeAiOauth": {accessToken, refreshToken, expiresAt, refreshTokenExpiresAt, scopes, subscriptionType, rateLimitTier}}`, `expiresAt` in **milliseconds** |
| Live token lifetime | access ≈ 8 h, refresh ≈ 27 d, `scopes` include `user:profile` |

Taken from the surveyed third-party clients, **not** independently verified here —
treat as design input, not fact:

- the endpoint 429s hard per credential, with `Retry-After` around **1300 s**, and
  retrying inside the penalty re-trips it (cclimit `Polling.swift`);
- the rate-limit bucket is keyed on the client fingerprint, and a missing or wrong
  User-Agent lands in a much stricter one (cclimit `UsageClient.swift`, citing
  `anthropics/claude-code#30930`);
- `claude setup-token` mints inference-only credentials that the endpoint answers
  with 403 — consistent with what `add-claude-usage` probing found.

## Goals / Non-Goals

**Goals**

- Keep today's zero-credential, zero-network behaviour as the untouched default.
- Never modify Claude Code's credential.
- Be a well-behaved client of an endpoint that punishes misbehaviour harshly.
- Make provenance and the impersonation trade-off visible rather than buried.

**Non-Goals**

- No remote hosts (each would need its own credential).
- No quota history; in-memory as before.
- No token minting, refreshing or OAuth flow of any kind.
- Not the `POST /v1/messages` rate-limit-header probe (see D2).

## Decisions

### D1. `/api/oauth/usage`, not the `/v1/messages` header probe

cclimit reads `anthropic-ratelimit-unified-{5h,7d}-*` headers off a throwaway
1-token `POST /v1/messages`. Rejected here:

- it **spends quota to measure quota** — small, but a monitoring tool should not
  consume the thing it monitors;
- it requires sending Claude Code's own system prompt as the first system block, or
  OAuth access to `/v1/messages` is refused — a deeper impersonation than a
  User-Agent;
- it yields only `5h` and `7d`; per-model windows still require this endpoint, so we
  would end up calling it anyway.

`/api/oauth/usage` is a plain GET, verified 200, and carries everything.

### D2. Source selection is configuration, and `push` stays the default

```
herdr-watch.claude-usage.source: push | pull | auto     # default: push
```

`push` must remain the default: the shipped design's headline property is that it
needs no credential and makes no outbound call, and that must not erode by default.

Under `auto` both sources run and the **most recently observed** reading wins. Not
"pull always wins": pull polls on an interval, so a statusline reading can legitimately
be newer. Comparison is on the observation time each source already records — the
same `capturedAt` the gauge shows.

`Registry.updateClaudeUsage(...)` currently accepts whatever it is given. It gains
per-source retention: keep the latest reading from each source, publish the winner.
Otherwise two sources at different cadences would flap the published snapshot back
and forth.

### D3. The fingerprint opt-in is a separate flag, and it fails closed

```
herdr-watch.claude-usage.pull.impersonate-claude-cli: false   # default
```

With `source` set to `pull`/`auto` and this flag left `false`, the pull source **does
not start** and logs why. It does not quietly send our own User-Agent: per the survey
that lands in a stricter bucket, so it would fail in a way the operator would have to
debug rather than choose.

Two settings rather than one, deliberately. "Turn on the pull source" and "present as
Claude Code" are different decisions, and folding the second into the first hides it.

The value sent is `claude-cli/<version> (external, cli)`, with `<version>` detected
from the installed Claude Code and falling back to a pinned constant. A stale version
is reported by the survey to risk a stricter bucket, so detection is preferred over a
constant.

### D4. Credential access: by account, read-only, re-read on 401

**The bug this design exists to avoid:** `Claude Code-credentials` held two items on
the reference machine, distinguished only by account, and looking up by service alone
returned the expired one. That single mistake produced a wrong conclusion that stood
for a whole change cycle. Two surveyed macOS clients have the same defect.

So: enumerate the candidates and pick by evidence — an unexpired `expiresAt`, and
`user:profile` present in `scopes` — rather than trusting the store's ordering. If
several qualify, take the one expiring latest. If none does, report not-configured
rather than trying a dead token.

Sources, tried in order:

1. macOS Keychain, service `Claude Code-credentials`, selected as above.
2. `~/.claude/.credentials.json` (same JSON shape) — the headless-Linux path, and the
   only path in the native/container deployment.
3. A configured override path.

**Read-only is absolute.** No writes, no refresh call, no deletion — including on
401. On 401 the store is re-read on the next attempt, because Claude Code refreshes
it in place and an 8-hour access token means that happens routinely. Nothing is
cached across a 401.

`expiresAt` is milliseconds; treating it as seconds silently makes every token look
ancient.

### D5. Poll policy: the server's word always wins

Mirrors cclimit's reasoning, which is the only field-tested account of this endpoint's
behaviour available.

```
poll-interval:  5m     # healthy
backoff-floor:  1m
backoff-cap:    2h     # MUST exceed the longest observed penalty (~1300 s)
retry-margin:   30s    # added on top of a server Retry-After
```

- A `Retry-After` overrides our schedule entirely — wait that long plus the margin.
- Without one, exponential backoff from the floor, capped.
- The cap must exceed the penalty or the source never recovers: that is the trap, and
  it is why the cap is 2 h rather than a comfortable-looking 5 m.
- No overlapping requests (`ConcurrentExecution.SKIP`, as the existing reader uses).
- 403 (wrong scope) is not retried at the normal cadence — it will not fix itself; it
  is reported and retried rarely.

Default 5 m is deliberately unhurried. The 5-hour window moves at most ~0.3 % per
minute, and the cost of being wrong here is a lockout measured in tens of minutes.

### D6. Model changes: provenance and per-model windows

```
ClaudeUsage {
  state, capturedAt, error                       // unchanged
  source:  STATUSLINE | ACCOUNT_API              // new; never null
  windows: { fiveHour, sevenDay }                // unchanged
  models:  [ { name, usedPercent, resetsAt } ]   // new; empty, never null
}
```

`models` is a **list, not fixed fields**. The set is open — `Fable`, `Opus`,
`Sonnet`, `Design` were seen, and the survey shows the upstream shape already migrated
once from `seven_day_<model>` keys to a `limits[]` array. Fixed fields would need a
code change per model; a list does not.

The statusline source always reports `source: STATUSLINE` and an empty `models` — it
has no per-model data, and empty is the honest representation, not a gap.

Parsing `limits[]`: prefer it over the legacy top-level `seven_day_<model>` keys,
which the survey reports as nulled out in newer responses. Match on
`scope.model.display_name`, carried through as-is rather than mapped to an enum — an
unknown model must survive to the UI.

`utilization` is a float and `resets_at` an ISO-8601 string here, unlike the
statusline payload's integer-ish percent and epoch seconds. Both are normalised at the
edge to the integer percent and epoch seconds the model already uses — the same
rounding rule the statusline hook now applies, so the two sources cannot disagree by a
rounding convention.

### D7. Wire representation

- **SSE / REST**: `source` and `models` are additive fields on the existing
  `claude_usage` payload. Clients that ignore them are unaffected.
- **Snapshot API**: `GET /api/v1/snapshot/usage` gains `source` (a string) and a
  `models` array shaped like `windows`. No `null` (§3.4): absent means an empty array.
  The frozen `compact` / `status` profiles are untouched and `protocolVersion` stays
  `1` — §7 treats new fields on an existing response as compatible.
- `severityCode` stays derived from the session and weekly windows only. Per-model
  windows are informational; folding them into the one integer a text-less indicator
  switches on would change what that integer means for existing devices.

### D8. UI

The gauge gains a source label next to the existing capture time — `statusline` or
`account api` — reusing the existing muted caption styling, no new colour tokens.

Per-model rows render under the session/weekly bars, one per entry in `models`,
visually subordinate: they are a breakdown, not a peer of the two windows that
actually gate you. Empty `models` renders nothing at all, so the `push` default looks
exactly as it does today.

## Risks / Trade-offs

- **We present as another client.** Unavoidable if the feature is to work at all
  (D3). Mitigated by making it a separate, plainly-named, default-off setting rather
  than a hidden constant — the operator decides knowingly. Worth restating: this is a
  trade-off accepted, not a problem solved.
- **A lockout is self-inflicted and long.** The 429 penalty compounds on retry. D5 is
  written around that single failure mode; the backoff cap is the load-bearing part.
- **Undocumented, unversioned endpoint.** A shape change breaks parsing. Degrading to
  the statusline source (spec) means the dashboard survives it; under `source: pull`
  alone it becomes visibly stale, which is the honest outcome.
- **A credential on the box.** Read-only, and never leaves the process. Never logged —
  including in error paths, which is where this kind of thing escapes.
- **Two sources can disagree.** Different observation times, and the account API sees
  usage from other machines that the statusline never will. The freshest-wins rule
  plus a visible source label makes that explicable rather than mysterious.
- **Native image.** The new records travel via `StreamEvent.data` and must be in
  `NativeReflectionConfig`; the HTTP client returns as a dependency.

## Migration Plan

Additive and reversible.

1. Ship with `source: push` — no credential read, no request, no visible change.
2. An operator wanting pull sets `source` and the fingerprint flag, and confirms the
   gauge labels its figures `account api`.
3. **Rollback:** set `source` back to `push`. Nothing persisted, no state to unwind.

## Open Questions

- **Whether `auto` should prefer pull on a tie.** Freshest-wins is unambiguous until
  two readings share a timestamp; the tiebreak is arbitrary and can be settled in
  implementation.
- **Whether `spend` and `extra_usage` are worth surfacing.** Present in the response,
  out of scope here; noted because the capture point makes them nearly free later.
- **Whether the opaque top-level keys** (`amber_ladder`, `cinder_cove`,
  `juniper_tide`, `nimbus_quill`, `tangelo`, …) ever carry quota. They are ignored;
  they look like feature flags, but that is inference, not evidence.

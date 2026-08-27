## 1. Confirm the payload on the target machine — DONE (2026-08-27)

Captured on the reference machine (Claude Code 2.1.247) by temporarily wrapping
`statusLine` with `tee`. **Payload matches design.md exactly; no design change needed.**

```json
"rate_limits": {
  "five_hour": { "used_percentage": 27, "resets_at": 1787803200 },
  "seven_day": { "used_percentage": 24, "resets_at": 1788206400 }
}
```

- [x] 1.1 Dump one real statusline payload
- [x] 1.2 `used_percentage` is an integer 0–100; `resets_at` is epoch seconds — confirmed
- [x] 1.3 Values agree with the rendered statusline (`5h 27% (38m) · 7d 24%`)
- [x] 1.4 Other fields present: `cost` (`total_cost_usd`, durations, lines +/-), `context_window` (`total_input_tokens`, `context_window_size`, `current_usage` incl. cache breakdown), `model`, `effort`, `fast_mode`, `thinking`, `session_id`, `session_name`, `workspace`, `cwd`, `transcript_path`, `output_style`, `prompt_id`, `exceeds_200k_tokens`, `version` — recorded as a design Open Question, **not built here**
- [x] 1.5 Shape matches design.md — no update required

## 2. Statusline hook

- [x] 2.1 Write the pass-through hook: read stdin fully, parse, extract `rate_limits`, forward stdin verbatim to the wrapped command given as argv, exit with the wrapped command's status
- [x] 2.2 Wrap all capture logic in a catch-all — any failure must still forward stdin and exit successfully (design D2)
- [x] 2.3 Write `~/.config/herdr-watch/claude-usage.json` atomically: temp file in the same directory + `rename()` (design D3); create the directory if missing
- [x] 2.4 Omit windows absent from the payload; never write a zero or a placeholder reset time
- [x] 2.5 Leave the previous record untouched when the payload has no `rate_limits` (normal before the first API response)
- [x] 2.6 Tests: payload with both windows; with one; with none; malformed input; unwritable target — in every case stdin is forwarded unchanged and exit status is the wrapped command's
- [x] 2.7 Verify the hook writes nothing of its own to stdout

## 3. Backend reader

- [x] 3.1 Add `quarkus-scheduler` to `backend/pom.xml` (no HTTP client needed)
- [x] 3.2 Create `ClaudeUsageConfig` (`@ConfigMapping(prefix = "herdr-watch.claude-usage")`): state-file path, poll interval, staleness threshold; add the block to `application.yaml`
- [x] 3.3 Create the `usage/` package with the `ClaudeUsage` record and window record per design D6 (state enum, nullable windows)
- [x] 3.4 Implement the mtime-poll reader: `stat` each tick, parse and publish only when mtime changed (design D4)
- [x] 3.5 Map a missing file to `NOT_CONFIGURED`; an unreadable or unparseable file to `STALE` with a reason when a previous snapshot exists
- [x] 3.6 Mark records older than the staleness threshold as `STALE`, keeping the figures and `capturedAt`
- [x] 3.7 Unit-test the mapping using the record shape in design D3 populated with the real values recorded in group 1 above (`five_hour` 27 / `resets_at` 1787803200, `seven_day` 24 / 1788206400), plus: one window only, no windows, garbage file, missing file, aged record

## 4. Publish

- [x] 4.1 Add the `volatile ClaudeUsage` field and `updateClaudeUsage(...)` to `Registry`, emitting `StreamEvent("claude_usage", …)` on the existing bus
- [x] 4.2 Suppress re-publishing when the snapshot is unchanged (spec: "Unchanged data")
- [x] 4.3 Confirm the initial `snapshot` handshake payload is unchanged (still `List<HostState>`)
- [x] 4.4 Add `GET /api/claude-usage`; annotate with `@Schema`/`@Operation` per repo conventions
- [x] 4.5 Register the new records in `NativeReflectionConfig`
- [x] 4.6 Regenerate `docs/api/openapi.yaml` and commit it in the same change — CI checks it for drift

## 5. Snapshot API surface for embedded clients

Design D7/D8. ESP32-class clients poll and cannot use SSE.

- [x] 5.1 Add the `SnapshotUsage` record in `snapshot/`: `protocolVersion`, `state`, `severityCode`, `capturedAt`, `windows[]`, annotated like the existing snapshot records
- [x] 5.2 Project `ClaudeUsage` → `SnapshotUsage` in `SnapshotProjection`: nullable windows become **omitted array entries**, `capturedAt` absent becomes `0`
- [x] 5.3 Map state + utilization to `severityCode` (0 unknown/not-configured, 1 ok, 2 warning, 3 critical, 4 exhausted) from the same constant the UI bands use
- [x] 5.4 Add `GET /api/v1/snapshot/usage` to `SnapshotResource` with `ETag: "usage-<capturedAt>"` and `If-None-Match` → `304`, per §3.10
- [x] 5.5 Assert no field can serialize as `null` in any state — §3.4 forbids it; cover `NOT_CONFIGURED` and all-windows-absent
- [x] 5.6 Regression-test that `/api/v1/snapshot/agents` is unchanged in all three profiles and `protocolVersion` is still `1`
- [x] 5.7 Register the new records in `NativeReflectionConfig`
- [x] 5.8 Document the endpoint in `docs/api/herdr-watch-snapshot-protocol.md` as a §7-compatible addition (new section, no version bump); regenerate `openapi.yaml`

## 6. Frontend

- [x] 6.1 Add `ClaudeUsage` types and the `claude_usage` variant to `lib/types.ts`
- [x] 6.2 Handle `claude_usage` in `hooks/useSse.ts`, storing it separately from the host map
- [x] 6.3 Build the gauge: per-window bar, reset time, capture time, account-scoped label; severity bands from `lib/theme.ts` helpers only, sharing the constant from 5.3 — no new hex literals
- [x] 6.4 Render it in `HostCard` only when `server.local === true` and state is `OK`/`STALE`
- [x] 6.5 Render the Compact-view equivalent
      — своя плитка `UsageTile` **последней в сетке**, наравне с карточками агентов и с тем же
      масштабированием шрифта: первая версия (узкая полоска в тулбаре) не читалась с дистанции,
      на которую Compact и рассчитан. Плитка входит в раскладку как ещё одна ячейка, поэтому
      последняя строка не съезжает. Вариант `dense` у `UsageGauge` удалён — им больше никто не пользуется.
- [x] 6.6 Visually distinguish `STALE`; render nothing at all when `NOT_CONFIGURED`
- [x] 6.7 `cd frontend && npm run typecheck`

## 7. Verify against the spec

- [x] 7.1 Without the hook installed: app boots, dashboard unchanged, snapshot reports `NOT_CONFIGURED`, nothing rendered
- [x] 7.2 Install the hook; confirm the statusline still renders identically and with no perceptible delay
      — installed in `~/.claude/settings.json` (backup: `settings.json.bak-before-herdr-watch-hook`).
      Same-moment A/B of the real `~/.claude/statusline.py` direct vs wrapped: stdout byte-identical,
      same exit status, empty stderr, +32 ms (one extra `python3` start). The live Claude Code session
      then wrote a genuine reading through the hook on its own (5h 34 % / 7d 25 %), which the native
      binary picked up and rendered on the local host card.
- [x] 7.3 Confirm the gauge appears on the local host card only, and matches the statusline's own numbers
- [x] 7.4 Delete the state file mid-run: dashboard degrades to stale, host frames and health keep updating normally
- [x] 7.5 Corrupt the state file: previous snapshot preserved and marked stale, no partial values
- [x] 7.6 Leave it untouched past the staleness threshold: state flips to `STALE` with the capture time shown
- [x] 7.7 Run two Claude Code sessions concurrently; confirm the file stays valid
- [x] 7.8 Confirm an SSE client that ignores `claude_usage` still receives `snapshot` and `host_update` unchanged

## 8. Build and document

- [x] 8.1 `./mvnw package` (fast-jar) and run the existing test suite green
- [x] 8.2 Native build with GraalVM for JDK 21; confirm the gauge works in the native binary (catches missing reflection registration)
- [x] 8.3 Document the `settings.json` install line and its removal in `README.md`
- [x] 8.4 Update `CLAUDE.md`: new `usage/` package, the hook script, `quarkus-scheduler`, and the note that quota only advances while a Claude Code session runs

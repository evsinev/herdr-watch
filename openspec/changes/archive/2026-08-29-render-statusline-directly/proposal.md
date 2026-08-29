# Render the status line directly, in one binary

## Why

Recording the Claude quota costs the operator two Python interpreter starts on
every status line refresh, on the interactive path of their editor:

```
Claude Code ──stdin JSON──► scripts/herdr-watch-statusline-hook.py   (records the quota)
                                └─ subprocess ──► ~/.claude/statusline.py   (renders the line)
```

The wrapper exists for one reason: at the time it was written the operator's own
renderer was already in place, and `add-claude-usage/design.md` D1 chose not to
touch it. That was right then. It has since produced three problems:

1. **The line itself is unversioned.** The renderer lives in the operator's home
   directory, outside this repository, on one machine. Its behaviour — which
   fields are shown, how utilization is coloured, how a missing field degrades —
   is the most visible surface herdr-watch has, and none of it is reviewable,
   testable or recoverable.
2. **Two process starts per tick.** ~60 ms of interpreter startup to render a line
   and compare six integers. It is why `statusLine.refreshInterval` is documented
   as a trade-off rather than simply recommended.
3. **The quota rules are tested manually or not at all.** The hook's acceptance
   logic — the rules that stop a lagging Claude Code session from overwriting a
   good reading — is the subtlest code in the feature and fixed a real flapping
   gauge. Its suite has never run in CI (`README.md`, "run it by hand"), and two
   of its rules appear in no requirement anywhere.

## What Changes

**One binary replaces both scripts.** It is what the operator configures as their
`statusLine` command. It reads the payload on stdin, writes one rendered line to
stdout, records the quota, and exits successfully — always.

- **New `statusline-rust/`**: a small Rust crate at the repository root, alongside
  `backend/`, `frontend/` and `tray-macos/`. Built with `cargo`, installed with
  `cargo install --path statusline-rust`, and released as a macOS arm64 and a
  linux x86_64-musl binary alongside the existing artifacts.
- **Pass-through wrapping is removed.** There is no wrapped command, no forwarded
  stdin, no adopted exit status, and no `127`. An operator who wants to keep their
  own renderer runs the binary in record-only mode next to it; the recipe goes in
  `README.md`.
- **The renderer's behaviour becomes a requirement**, ported field for field, with
  a golden-output corpus and unit tests. Its previous form is vendored under
  `reference/statusline.py` so the port is reviewable.
- **The acceptance rules become requirements.** The "a reading must carry the
  session window" rule and the anti-regression rule are written down for the first
  time, and their 35-case suite moves into `cargo test` and into CI.
- **The colour thresholds and the transcript fallback become arguments.** Today the
  thresholds are constants in a personal file and the transcript read is
  unconditional; both become explicit, with the transcript read **off** by default.

### What stays exactly as it is

The state file is a cross-process, cross-version rendezvous point that several
Claude Code sessions write concurrently and that the backend polls by mtime. Its
format, its acceptance rules, its atomic-write discipline and — critically — the
rule that an unchanged reading does not touch the file **or its mtime** are
carried over unchanged. This change rewrites the writer; it does not renegotiate
the contract.

## What this change deliberately does not do

- **No account API, no credential, no network.** The binary's only input is the
  payload on stdin. The pull source stays entirely in the backend
  (`backend/.../usage/pull/`) and is not touched, not duplicated, and not moved.
- **No external processes.** No `git`, no `claude --version`, no shelling out for
  anything. Repository state is not shown in the line.
- **No backend or frontend changes.** `ClaudeUsageReader`, the registry, the SSE
  event, the REST and Snapshot endpoints and the UI are untouched; edits in Java
  and YAML are comments that name the old script path.
- **No new features in the line.** The port reproduces what the renderer does
  today. Additions are a later change, judged on their own.
- **No file locking.** Last-writer-wins is deliberate (`add-claude-usage/design.md`
  D3) and stays.
- **The operator's own renderer is not deleted.** It is theirs, it is outside this
  repository, and it is the rollback path.

## Capabilities

### Modified Capabilities

- `claude-usage`: the tool that captures the quota now *is* the status line rather
  than wrapping it — it renders the line, and the rules by which it accepts or
  rejects a reading become explicit requirements.

<!-- `claude-usage-pull` is untouched: no delta. -->

## Impact

**New**
- `statusline-rust/` — the crate (10 modules, ~1000 lines including tests), its
  golden-output corpus, and its `Cargo.lock`.
- `.github/workflows/ci.yml` — a separate `statusline` job (`fmt`, `clippy`,
  `test`), so a Rust failure cannot block `./mvnw verify`. The hook's suite runs in
  CI for the first time.
- `.github/workflows/release.yml` — a two-target build job; the artifacts are
  picked up by the existing `dist/**/herdr-watch-*` glob.

**Removed**
- `scripts/herdr-watch-statusline-hook.py`, `scripts/test_herdr_watch_statusline_hook.py`.
- `scripts/capture-usage-fixture.py` — unrelated to the status line; it is the tool
  that captured the live pull fixtures. Removed on the operator's explicit
  instruction, noted here so its absence is not a surprise later.

**Documentation (touched)**
- `README.md` (install, removal, `refreshInterval`, transparency, repository
  layout, how to run the tests), `CLAUDE.md` (architecture diagram, invariants,
  commands, key files), `docs/api/herdr-watch-snapshot-protocol.md`, and the
  comments naming the script path in `application.yaml` and `ClaudeUsageConfig`.

**Operational**
- The operator edits one line in `settings.json` and can revert it just as fast.
- A tick costs ~3 ms instead of ~60 ms. `refreshInterval: 300` stays right anyway:
  the state file's semantics are unchanged, so a shorter interval buys nothing but
  more no-op runs.
- Building the status line now needs a Rust toolchain, or a released binary.

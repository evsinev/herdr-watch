# Design: one binary that is the status line

## Context

See `proposal.md — Why` for motivation and `specs/claude-usage/spec.md` for the
behaviour contract. The renderer being ported is vendored verbatim at
`reference/statusline.py`; the capture logic being ported is
`scripts/herdr-watch-statusline-hook.py`, and its 35-case suite is
`scripts/test_herdr_watch_statusline_hook.py`.

Constraints that shape the approach:

- **The state file is a rendezvous point, not private storage.** Several Claude
  Code sessions of different versions write
  `~/.config/herdr-watch/claude-usage.json` concurrently, with no lock, while
  `ClaudeUsageReader` polls it. Two properties of the current writer are
  load-bearing for the reader and cannot be renegotiated by a rewrite:
  the file is re-parsed **only when its mtime changes**, and `capturedAt` drives
  the `STALE` state. A writer that touches the file when nothing changed breaks
  both at once.
- **The reader is tolerant, and that tolerance hides divergence.**
  `ClaudeUsageReader.window()` re-rounds any fractional `used_percentage` through
  `Percents.toWhole` (`Math.round`, half-up). If the writer rounds differently, the
  push and pull sources disagree by a convention rather than by data — which is
  precisely what `Percents` was extracted to prevent.
- **The interactive path is unforgiving.** The command runs inside the operator's
  editor, on a timer. A non-zero exit, a panic message on stderr, or a blank line
  is immediately visible and has no diagnostic attached.
- **The payload is a document, not a schema.** Every field may be absent, `null`,
  or of the wrong type; `rate_limits` is absent entirely until the session's first
  API response. The reference renderer type-checks at every hop for this reason.
- **`used_percentage` is not always an integer.** It is a fraction × 100, so
  live values arrive as e.g. `7.000000000000001`. Rejecting non-integers is the
  historic bug that made the 5-hour bar come and go.

## Goals / Non-Goals

**Goals**

- One process, no subprocess, no network, no credential, no external command.
- Byte-identical rendered output to `reference/statusline.py`, so the cutover is
  verifiable by `diff` rather than by eye.
- The state file's acceptance rules preserved exactly, and written down as
  requirements for the first time.
- The 35-case contract runs in CI.

**Non-Goals**

- Any change to the state file's format, to the backend, or to the frontend.
- New content in the rendered line.
- Configuration files. Arguments only.
- Windows.

## Decisions

### D1 — One command, not a wrapper. Supersedes `add-claude-usage` D1.

The binary *is* the status line: it reads stdin, prints the line, records the
quota, exits 0. No wrapped command, no forwarded stdin, no adopted exit status,
no `127`.

`add-claude-usage` D1 chose a pass-through wrapper so the operator's own script
stayed untouched. Reversing it is what makes the rendered line reviewable and
testable at all, and it removes a process start from a path that runs on a timer.

The operator who wants to keep their own renderer is not stranded — see D9.

### D2 — Transparency survives, restated for a renderer.

`add-claude-usage` D2 said: any capture failure is swallowed, stdin is still
forwarded, nothing of ours reaches stdout. With no wrapped command the obligation
becomes stronger, not weaker: **we are now the only thing that can draw the line**.

- Silent panic hook (`panic::set_hook(Box::new(|_| {})))` as the first statement of
  `main` — the default hook writes `thread 'main' panicked at …` to **stderr**,
  which the editor will surface.
- `catch_unwind` separately around render and around capture: a panic in render
  yields an empty line, a panic in capture leaves the record untouched, and
  neither affects the other.
- The exit status is **always 0**.
- Nothing is ever written to stderr. Enforced by
  `#![deny(clippy::print_stderr)]`, and asserted for every input in the test
  corpus.
- **`panic = "abort"` is deliberately not set.** It would kill the process with
  `SIGABRT`: no line, a non-zero status, possibly a shell message — exactly the
  failure this decision exists to prevent. The cost of keeping unwind is unwind
  tables in a stripped binary and no measurable startup time. `Cargo.toml` carries
  a comment saying so, because this is the kind of line someone "optimizes" later.
- `write_all`, never `println!`: `println!` **panics** on a closed pipe.

### D3 — Render before capture; one parse feeds both.

The Python hook had to capture first, because capture happened before the `exec`.
We are free to choose, and the better order is render → capture: if the process is
killed by a timeout mid-write, the line has already been drawn. The payload is
parsed once and both halves read the same `Value`.

### D4 — Two rounding rules, on purpose.

Three languages disagree on halves: Python `round()` is half-to-even, Rust
`f64::round()` is half-away-from-zero, Java `Math.round` is half-up.

- **D4a — recorded percentages round half-up.** The Java reader re-rounds through
  `Percents.toWhole` (half-up); writer and reader must not diverge by convention.
  Negative values are rejected upstream, so on the valid domain `f64::round()` is
  exactly `Math.round`, and using it avoids reproducing Java's
  `0.49999999999999994` edge case that a literal `(x + 0.5).floor()` would.

  Divergence from Python is confined to exactly-representable `.5` with an even
  floor (2.5, 4.5, …), where Python wrote 2 and we write 3 — as Java would have.
  **The direction is always upward**, so a Rust write following a Python write can
  never be rejected as a regression by D5's rules. That property is asserted by a
  test, not by this paragraph.

- **D4b — rendered percentages round half-to-even.** Byte parity with
  `reference/statusline.py` is what makes the cutover verifiable by `diff`. Without
  it the context bar differs by one cell whenever `pct/100*10` lands exactly on
  `x.5` — at 25 %, 45 %, 65 % and 85 % — and the A/B would hit one within a day.

The asymmetry is defensible in one sentence each: *the record is read by Java; the
line replaces a Python script.*

Note that the reference renderer itself uses two different rules in the same file:
a payload-supplied context percentage is **truncated** (`int(pct)`), a computed one
is **rounded**. That is not a typo and is preserved.

### D5 — The acceptance rules are the contract, and they move into the type system.

Ported unchanged from the hook, and now specified:

- A reading without a usable `five_hour` is discarded whole. Older Claude Code
  versions emit `rate_limits` carrying only `seven_day`, with values that agree
  neither with the account nor with each other (observed: 7 / 20 / 26 / 29 against
  a true 34). Requiring the window the old shape never has filters them out without
  tracking versions.
- A reading that goes backwards is discarded whole — a window whose `resets_at`
  precedes the recorded one, or a lower utilization inside the window already
  recorded. Utilization inside a fixed window only grows and reset times only move
  forward, so backwards means "a lagging session is talking".
- A window whose `resets_at` moved **forward** may drop to zero: that is a genuine
  reset.
- A corrupt recorded window is skipped rather than protected — otherwise garbage in
  the file freezes it permanently.

`Reading.five_hour` is typed as `Window`, **not** `Option<Window>`. The mandatory
window is then an invariant of the type rather than a check someone can delete.

### D6 — `unchanged` compares numbers, not JSON representations.

In Python `27 == 27.0`; in Rust `Value::Number(27) != Value::Number(27.0)`, because
`PartialEq` compares representation. A direct `Value` comparison would report
"changed" on every tick against a file some other writer produced, rewrite it, move
the mtime, and turn `capturedAt` into "when the command last ran" — killing the
staleness indicator the whole feature rests on.

So the comparison reproduces Python's semantics explicitly: numeric equality via
`as_f64()`, exactly two keys per window (an extra key means changed, as in Python),
absence equal only to absence, and `capturedAt` required to be an integer JSON
number (a float forces a rewrite, matching `isinstance(x, int)`).

A test seeds the file with the **Python writer's exact bytes** and asserts the
mtime does not move. That is the whole cross-version migration guarantee.

### D7 — Atomic write by hand, at mode 0600. Restates `add-claude-usage` D3.

Temp file in the **same directory** (same filesystem) created with
`create_new(true)` — `O_CREAT|O_EXCL`, race-free — then `write_all`, `sync_all`,
`rename`, and removal of the leftover on any error path.

`.mode(0o600)` is explicit and not optional: `mkstemp` creates 0600 and
`os.replace` preserves it, whereas a plain `create_new` yields `0644 & ~umask`.
Quietly widening permissions on a file in the user's config directory during a
"rewrite in another language" is not a trade we get to make silently.

No locking. Last-writer-wins is deliberate and stays: the writers' whole contract
is to not touch the file when nothing changed, and a lock would serialize
processes that mostly do nothing.

The record is serialized by hand — three keys, all integers, in the Python key
order. The document is tiny and fully under our control, "unchanged ⇒ identical
bytes" becomes trivially true, and no float formatter is ever on the path.

### D8 — The payload is read as a dynamic document.

`serde_json::Value` plus defensive accessors, not `#[derive(Deserialize)]`. Every
field is optional and may be of the wrong type, so a derived struct would be
`Option<Value>` per field anyway — `Value` with a proc-macro tax. `Value::Bool` is
structurally not `Value::Number`, so Python's `isinstance(value, bool)` guard
disappears rather than being ported.

One runtime dependency, `serde_json`. Not `clap`, `anyhow`, `tempfile` or `libc`:
`std` already has `sync_all`, `rename`, `create_dir_all` and `OpenOptionsExt::mode`,
the error policy is "swallow", and the argument grammar is a `match`. This runs on
every refresh; the dependency budget is part of the design.

### D9 — Arguments only, and an unknown argument is never an error.

Flags cover exactly three things: which half runs (`--no-capture`,
`--capture-only`), what the operator can tune (`--warn-at`, `--critical-at`,
`--transcript-fallback`, `--state-file`, `--no-color`), and `--version`.

**An unrecognised argument is ignored and the default behaviour runs.** This is D2
applied to `argv`: a typo in `settings.json`, or a future Claude Code that passes
an argument of its own, must not blank the operator's status line.

There is no `--wrap`. It would cost a subprocess spawn, argv quoting inside
`settings.json`, the `127` convention and a third of the test suite, to serve one
case — an adopter who already has a renderer they want to keep. That case is served
at zero cost by `--capture-only`, which the tests need anyway:

```sh
sh -c 'p=$(cat); printf %s "$p" | herdr-watch-statusline --capture-only; printf %s "$p" | ~/.claude/my-statusline.sh'
```

### D10 — The transcript fallback is opt-in; the operator's old renderer is theirs to retire.

The reference renderer, when `context_window` is absent from the payload, tail-reads
256 KiB of the session transcript to estimate token usage. It is the only thing the
renderer reads besides stdin. It stays available but **off by default**: the
principle is that the command reads its input and nothing else, and an operator who
wants the estimate asks for it with `--transcript-fallback`. Without it the context
element simply drops out, like any other element with no data.

`~/.claude/statusline.py` is not deleted by this change. It is personal, outside the
repository, the A/B oracle during cutover and the rollback path afterwards. The
operator retires it themselves once the new line has been on screen for a while.

## Risks

1. **A blank or broken status line** — the most visible surface there is, refreshed
   constantly. Mitigated by D2 in full, plus a golden corpus and an "stderr is
   empty, stdout is exactly one line" assertion on every test input.
2. **Silently losing the acceptance logic** (D5). It is the subtlest code in the
   feature, it fixed a real bug, and until this change no requirement covered it.
   Mitigated by porting it from a 1:1 test-mapping table, by the type-level
   invariant, and by writing the requirement down.
3. **mtime stability lost at the language boundary** (D6) — the failure that would
   look like success: everything renders, and the staleness indicator quietly dies.
4. **Rounding drift at cutover** (D4). Bounded and directional; asserted by test.
5. **`~/.cargo/bin` missing from the shell's `PATH`** — a blank line with no
   diagnostic. Mitigated by documenting an absolute path in `settings.json`.
6. **Permission widening** 0600 → 0644 if `.mode()` is ever dropped (D7).
7. **The musl release build** — release workflows break on tag pushes, the worst
   possible moment. Mitigated by a `workflow_dispatch` dry run before the first tag.
8. **The temptation to add a lock, or to lower `refreshInterval` now that ticks are
   cheap.** Both are addressed above; neither is an improvement.

## Rejected alternatives

- **Port the hook only, keep the wrapper and the Python renderer.** Cheapest, and
  leaves the most visible surface of the feature unversioned and untested — which
  is the main thing this change is for.
- **Let the binary fetch the quota from the account API when the payload lacks it.**
  It would duplicate `ClaudeUsageApiClient`, the credential chain, the backoff
  policy and the impersonation opt-in — every one of which has already cost a
  change cycle to get right — in a second language, inside a process that must
  finish in milliseconds and never fail. The pull source stays in the backend.
- **`#[derive(Deserialize)]` for the payload.** See D8.
- **`panic = "abort"`.** See D2.
- **A configuration file.** Two thresholds and three switches do not need one, and
  a parse error in it would have to be swallowed silently — a setting that fails
  invisibly is worse than no setting.

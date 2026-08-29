# herdr-watch-statusline

The Claude Code status line for herdr-watch. One command does two things with the
same payload: it renders the operator's status line, and it records the Claude
subscription quota into `~/.config/herdr-watch/claude-usage.json`, which the
herdr-watch backend polls.

```
Opus 5 · high | Ctx [██░░░░░░░░] 185.3k/1.0M (19%) | +1055/-37 | Cost $8.88 · 43m (api 19m) | 5h 43% (2h13m) · 7d 18%
```

It reads its input and nothing else: no network, no credential, no subprocess, no
repository state. The only optional exception is `--transcript-fallback`.

## Install

```sh
cargo install --path statusline-rust
```

Then, in `~/.claude/settings.json` — an **absolute** path, because the shell that
runs the command may not have `~/.cargo/bin` on its `PATH`, and the failure mode is
a silently blank status line:

```json
"statusLine": {
  "type": "command",
  "command": "/Users/you/.cargo/bin/herdr-watch-statusline",
  "refreshInterval": 300
}
```

Released binaries for macOS arm64 and Linux x86_64-musl are attached to each
release as `herdr-watch-statusline-<tag>-<platform>`.

## Flags

| Flag | Default | Effect |
|---|---|---|
| *(none)* | | render the line and record the quota |
| `--no-capture` | off | render only; no state file is touched |
| `--capture-only` | off | record only; nothing on stdout |
| `--transcript-fallback` | **off** | when the payload does not report context consumption, estimate it from the tail of the session transcript |
| `--warn-at <pct>` | `60` | where the line turns yellow |
| `--critical-at <pct>` | `85` | where it turns red |
| `--state-file <path>` | `$HERDR_WATCH_USAGE_FILE`, else `~/.config/herdr-watch/claude-usage.json` | where the quota is recorded |
| `--no-color` | off (`NO_COLOR` is honoured) | no ANSI codes |
| `-V`, `--version` | | print the version; stdin is not read |

An unrecognised argument is **ignored**, not refused: a typo in `settings.json`
must not blank the status line.

### Keeping your own renderer

There is no wrapping mode. Record alongside whatever you already have:

```json
"statusLine": {
  "type": "command",
  "command": "sh -c 'p=$(cat); printf %s \"$p\" | herdr-watch-statusline --capture-only; printf %s \"$p\" | ~/.claude/my-statusline.sh'"
}
```

## Development

```sh
cargo fmt --check
cargo clippy --all-targets -- -D warnings
cargo test
```

All three run in CI as a separate job, so a failure here cannot block the backend
build.

The behaviour contract is `openspec/specs/claude-usage/spec.md`; the numbered
decisions the code cites (D1…D10) are in
`openspec/changes/render-statusline-directly/design.md`. The Python renderer this
binary ports is vendored verbatim under that change's `reference/`, and
`tests/data/*.expected` were frozen by diffing the two byte for byte.

### Two things worth knowing before changing anything

**The state file is a rendezvous point, not private storage.** Every Claude Code
session on the machine writes it, those sessions may be of different versions, and
there is no lock. `src/capture.rs` decides which readings are accepted and — just
as importantly — when the file is left completely alone. When the figures have not
changed the file is not rewritten **at all**, because the backend re-parses it only
when its modification time moves and `capturedAt` has to keep meaning "when the
figures last moved".

**Rounding is asymmetric on purpose.** Recorded percentages round half-up, to agree
with the Java reader that re-rounds them; rendered percentages round half-to-even,
to stay byte-identical to the renderer being replaced. See `src/round.rs`.

## 1. Crate skeleton and the checks that guard it

Design D2, D8. Nothing works yet; the point is that the guard rails exist before
any logic does.

- [x] 1.1 `statusline-rust/Cargo.toml`: `[lib] herdr_watch_statusline` + `[[bin]] herdr-watch-statusline`, edition 2024, one runtime dependency (`serde_json`, `default-features = false`, `std`), `tempfile` as a dev-dependency only
- [x] 1.2 `[profile.release]`: `opt-level = "s"`, `lto`, `codegen-units = 1`, `strip`. **No `panic = "abort"`** — with a comment naming D2, because this is a line people "optimize" later
- [x] 1.3 `rustfmt.toml` and `clippy.toml` in the crate, so a local run and CI agree
- [x] 1.4 Crate-level `#![deny(clippy::unwrap_used, clippy::expect_used, clippy::panic, clippy::indexing_slicing, clippy::unreachable, clippy::todo, clippy::print_stderr)]`, allowed inside `#[cfg(test)]`
- [x] 1.5 Module skeleton — one responsibility per file, `main.rs` only wires them: `main.rs`, `lib.rs`, `args.rs`, `json.rs`, `round.rs`, `capture.rs`, `store.rs`, `fmt.rs`, `render.rs`, `transcript.rs`
- [x] 1.6 `main.rs`: silent panic hook first, then arguments → stdin → one parse → render → capture → exit 0 (D3). `write_all`, never `println!`
- [x] 1.7 `.github/workflows/ci.yml`: a **separate** `statusline` job — `cargo fmt --check`, `cargo clippy --all-targets -- -D warnings`, `cargo test --locked` — so a Rust failure cannot block `./mvnw verify`
- [x] 1.8 Commit `Cargo.lock` (this is an application, and CI runs `--locked`); add `statusline-rust/target/` to `.gitignore`
- [x] 1.9 Green CI on a binary that prints an empty line

## 2. Arguments

Design D9.

- [x] 2.1 `args.rs`: hand-rolled parse, no `clap`
- [x] 2.2 `--no-capture`, `--capture-only`, `--transcript-fallback`, `--warn-at <pct>`, `--critical-at <pct>`, `--state-file <path>`, `--no-color`, `-V`/`--version`
- [x] 2.3 Defaults: thresholds 60 / 85 (what the ported renderer uses today), transcript fallback **off**, colour on unless `--no-color` or `NO_COLOR`
- [x] 2.4 **An unrecognised argument is ignored**, never an error — D2 applied to `argv`
- [x] 2.5 A malformed threshold value (`--warn-at banana`, out of range) falls back to the default rather than failing
- [x] 2.6 `--version` does not read stdin
- [x] 2.7 Tests for each of the above

## 3. Reading the payload, and rounding

Design D4, D8.

- [x] 3.1 `json.rs`: `num` (finite `Value::Number` only — `Value::Bool` is structurally excluded, so Python's `isinstance(x, bool)` guard disappears rather than being ported), `text` (non-blank string, returned **unstripped**), and object chaining that yields `&Value::Null` for a missing hop
- [x] 3.2 `round.rs`: `half_up` for the record (D4a) and `half_even` for the line (D4b), each with the decision cited in a comment
- [x] 3.3 `to_i64` for `resets_at`: exact `as_i64` when available, else truncate a finite float — Python's `int()` and Java's saturating cast agree there
- [x] 3.4 Tests: `7.000000000000001 → 7`, `7.4 → 7`, `7.6 → 8`, `0.2 → 0`, `99.5 → 100`, `104.7 → 100` (clamped, not dropped), and **`2.5 → 3`** pinning the deliberate divergence from Python
      — плюс `2.5 → 3` и свойство «half-up ≥ half-even» на всей области 0–100 (`src/round.rs`).
- [x] 3.5 Test the property D4a rests on: half-up is never below half-even, so a record written here after one written by the Python hook can never be read as a regression
- [x] 3.6 Tests: `-1`, `-0.5`, `true`, `"7"`, `null`, `[7]` all rejected; `resets_at` of `0` or negative rejected; a float `resets_at` accepted

## 4. The record: acceptance, comparison, writing

Design D5, D6, D7. **This is the section that matters**; it is ported from
`scripts/herdr-watch-statusline-hook.py` and its rules fixed a real flapping gauge.

- [x] 4.1 `capture.rs`: `Window`, and `Reading` with `five_hour: Window` — **not** `Option` — so the mandatory window is an invariant of the type (D5)
- [x] 4.2 `windows_of`: a reading with no usable session window is discarded whole
- [x] 4.3 `regresses`: per window, only where both sides have it and the recorded figures are readable; an earlier reset time, or the same reset time with lower utilization, discards the **whole** reading
- [x] 4.4 A window whose reset time moved forward may drop to zero — assert it is not read as a regression
- [x] 4.5 A corrupt recorded window is skipped, not protected (otherwise garbage freezes the file forever)
- [x] 4.6 `unchanged` per D6: numeric equality via `as_f64`, exactly two keys per window, absence equal only to absence, `capturedAt` required to be an **integer** JSON number
- [x] 4.7 `decide` returns either "leave it alone" or the exact bytes to write — the write path has no branches of its own
- [x] 4.8 `store.rs`: path resolution — `--state-file`, else `$HERDR_WATCH_USAGE_FILE` (**empty means unset**, matching Python's falsiness), else `~/.config/herdr-watch/claude-usage.json`; `~` expanded from `$HOME`
      **ИСПРАВЛЕНО 2026-08-29 после архивации.** Константа пути по умолчанию была
      записана без ведущего `~` (`.config/herdr-watch/claude-usage.json`), поэтому
      без `HERDR_WATCH_USAGE_FILE` путь оставался относительным и `absolute()`
      разрешал его от **текущего каталога** — запись уходила туда, откуда редактор
      запустил статус-строку, а бэкенд, следящий за настоящим файлом, навсегда
      остался бы в `NOT_CONFIGURED`. Из-за этого же первая проверка 7.8 ничего не
      доказывала: «mtime не сдвинулся» было верно потому, что писали не туда.
      Зафиксировано тестом `the_default_record_lives_under_home_not_under_the_working_directory`
      и перепроверено на живом файле после переустановки.
- [x] 4.9 Atomic write (D7): `create_dir_all`, temp file in the **same** directory via `create_new(true).mode(0o600)`, `write_all`, `sync_all`, `rename`, leftover removed on every error path
- [x] 4.10 Serialize by hand: three keys, all integers, Python key order, `seven_day` omitted when absent
- [x] 4.11 Unit tests for 4.2–4.6 driven by `json!` literals with `now` injected — no hidden clock anywhere

## 5. The line

Design D4b, D10. Ported field for field from `reference/statusline.py`.

- [x] 5.1 `fmt.rs`: colours, `paint`, the severity scale, `fmt_num` (`1.0M` / `1.5k` / integer), `fmt_span` (`5h01m` / `43m` / `18s`), `fmt_left` (never below `1m` while the reset is ahead)
- [x] 5.2 `transcript.rs`: split the tail read (I/O) from a **pure scanner over `&str`**, so the scanner is unit-testable without a file
- [x] 5.3 Model element: display name or id, effort, `fast`, `no-think`, a non-default output style
- [x] 5.4 Context element: 10-cell bar, size from the payload or 1M/200k by model id, a payload-supplied percentage **truncated** and a computed one **rounded** — two rules in one element, preserved deliberately; non-zero consumption always shows at least one filled cell
- [x] 5.5 Lines-changed element, dropped when both figures are zero or absent (preserving Python's `or 0`, where `0.0` is falsy — do not "fix" this into an `is_none` check)
- [x] 5.6 Cost element, with duration only from the first minute of the session
- [x] 5.7 Quota element: remaining time only while the reset is ahead
- [x] 5.8 Every element drops silently on absent or wrongly-typed data; `render` cannot fail into anything but an empty line
- [x] 5.9 `now` injected as a parameter — the line is a pure function of payload and time
- [x] 5.10 Unit tests per element, and one for the bar at exactly 25 % (two cells) pinning D4b against a later "simplification" to `f64::round()`

## 6. The contract, end to end

`scripts/test_herdr_watch_statusline_hook.py` has **35** cases and is the executable
specification. Port it by table; two cases die with the wrapper.

- [x] 6.1 `tests/capture.rs` driving the real binary via `env!("CARGO_BIN_EXE_herdr-watch-statusline")` with `HERDR_WATCH_USAGE_FILE` in a temp directory, nested so directory creation is exercised
- [x] 6.2 Cases 1–7: both windows recorded; one window absent rather than zero; an incomplete session window creates **no file at all**; no quota leaves the record untouched; malformed and unexpected shapes record nothing; empty stdin
- [x] 6.3 Cases 13–19: the modification time does not move on an identical payload, nor when only rounding noise changed; a changed figure rewrites and advances the capture time; a reset-time change alone counts; a window disappearing counts; a corrupt file is repaired; a record with no capture time is rewritten
- [x] 6.4 Cases 20–29: the old-client shape is ignored entirely and cannot overwrite a good record; a newer full reading still wins; every anti-regression case from §4
- [x] 6.5 Case 30: an unwritable target still renders the line and exits 0
- [x] 6.6 Cases 32–33 inverted for a renderer: standard output is exactly our line, **standard error is empty on every input**, and record-only mode prints nothing
- [x] 6.7 Cases 31 and 34 (`exit status is the wrapped command's`, `missing command → 127`) are **deleted** — there is no wrapped command
- [x] 6.8 `tests/concurrency.rs`: eight concurrent record-only processes, all exit 0, the file parses and holds one of the eight values
- [x] 6.9 New: a file written by the **Python hook's exact bytes** is read as unchanged and its modification time does not move (D6 — the cross-version migration guarantee)
- [x] 6.10 New: a recorded `27.0` equals a fresh `27`; an extra key in a recorded window counts as changed; a floating-point `capturedAt` forces a rewrite
- [x] 6.11 New: the state file's mode is `0600` after a write
- [x] 6.12 New: invalid UTF-8 on stdin, and stdin closed entirely — one blank line, exit 0, no file, empty standard error
- [x] 6.13 `tests/render.rs`: golden corpus from `tests/data/*.json` + `*.expected`, populated in §7
      — 15 payload'ов; эталоны сняты **питоновским** рендерером и совпали байт в байт
      с Rust на всех 15 (`tasks.md §7.3` прогнан на синтетическом корпусе досрочно).

## 7. Cutover

Nothing is deleted until this section has run.

- [x] 7.1 `cargo install --path statusline-rust`
      — 361 KB в `~/.cargo/bin/herdr-watch-statusline`; тик ~3.4 мс против ~55 мс у двух
      питоновских стартов.
- [~] 7.2 Collect a live payload corpus by temporarily wrapping the current command in `sh -c 'tee -a … | python3 ~/.claude/statusline.py'`; add the synthetic edges by hand — no quota, a `[1m]` model, percentages of exactly 25/45/65/85, a reset in the past, the transcript fallback
      — синтетические края сняты (15 payload'ов, `tests/data/`): без квоты, модель `[1m]`,
      проценты ровно 25/45/65/85, сброс в прошлом, неверные типы, пустой payload, флаги
      модели, стоимость с длительностью и без. **Живой корпус не собирался** — для него
      нужен час обычной работы оператора; синтетика покрывает все детерминированные ветки.
- [x] 7.3 A/B the line, byte for byte: `diff <(python3 reference/statusline.py < p.json | xxd) <(herdr-watch-statusline --no-capture < p.json | xxd)` must be empty for every payload, including the four bar-rounding cases (D4b). A difference here is a port bug, not an accepted divergence
      — 15/15 совпали байт в байт с `reference/statusline.py`, включая все четыре
      случая округления полосы (25/45/65/85 %).
- [x] 7.4 Freeze the passing pairs as `tests/data/*.expected`
- [x] 7.5 A/B the record: replay the same sequence through both writers into two state files, comparing parsed JSON and the pattern of modification-time changes step by step
      — 12 шагов (рост, шум округления, откат внутри окна, уже сброшенное окно, откат
      только в `seven_day`, форма старого клиента, отсутствие `rate_limits`, мусор,
      настоящий сброс, дробный процент): обе реализации приняли и отвергли ровно одни и
      те же показания и одинаково решали, трогать ли файл.
- [x] 7.6 Run `python3 scripts/test_herdr_watch_statusline_hook.py` one last time, green, and record that it was
      — 35 tests, OK (запущен из `git show HEAD:` после удаления файлов).
- [x] 7.7 Back up `~/.claude/settings.json`, then point `statusLine.command` at the **absolute** path of the installed binary and set `refreshInterval: 300`
      — бэкап `~/.claude/settings.json.bak-before-rust-statusline`; `refreshInterval` по
      решению оператора НЕ выставлен.
- [x] 7.8 Verify in order: the real record's modification time does **not** move on the first run over unchanged figures; the dashboard gauge stays `OK`; the line looks as it did; the capture time advances only when the figures move
      — mtime реальной записи не сдвинулся при неизменившихся цифрах (1787969212 до и
      после), запись осталась байт в байт питоновской; `GET /api/claude-usage` → `state:
      OK`, 5h 28 % / 7d 40 %, тот же `capturedAt`; строка отрисовалась как прежде.
- [x] 7.9 Leave `~/.claude/statusline.py` on disk — it is the rollback path and the oracle; the operator retires it themselves (D10)
      — файл на диске не тронут; дословная копия лежит в `reference/statusline.py`.

## 8. Retire the Python and the documentation that describes it

- [x] 8.1 `git rm scripts/herdr-watch-statusline-hook.py scripts/test_herdr_watch_statusline_hook.py`
- [x] 8.2 `git rm scripts/capture-usage-fixture.py` — unrelated to the status line; it is the tool that captured `backend/src/test/resources/usage-pull/oauth-usage-live-*.json`, against an endpoint that punishes a retry with ~22 minutes of 429. Removed on explicit instruction; noted in `proposal.md` so its absence is not a surprise
      — удалён по прямому указанию; в `proposal.md` записано, что он не имел отношения
      к статус-строке и снимал живые фикстуры pull-ветки.
- [x] 8.3 `.gitignore`: drop the now-dead `# Python (scripts/)` section
- [x] 8.4 `README.md`: the install block, the removal instructions, the `refreshInterval` rationale, the transparency paragraph, the record-only recipe for operators keeping their own renderer, the repository layout, and the "how to run the tests" line
- [x] 8.5 `CLAUDE.md`: architecture diagram, invariants, commands, key files
- [x] 8.6 `docs/api/herdr-watch-snapshot-protocol.md`: the reference to the hook script
- [x] 8.7 Comments naming the old script path in `backend/src/main/resources/application.yaml` and `usage/ClaudeUsageConfig.java` — comments only, no behaviour

## 9. Release

- [x] 9.1 `.github/workflows/release.yml`: a matrix job for `aarch64-apple-darwin` and `x86_64-unknown-linux-musl` (with `musl-tools`), artifacts named so the existing `dist/**/herdr-watch-*` glob picks them up
- [x] 9.2 Add the job to the release job's `needs:` and mention the binaries in the release body
- [x] 9.3 A `workflow_dispatch` dry run **before** the first real tag — musl builds are where release workflows break, and a tag push is the worst moment to find out
      — прогнан 2026-08-29 (run 33228796351): обе матричные цели зелёные, musl-сборка
      даёт `static-pie linked, stripped`, macOS-бинарник arm64 запускается и рендерит.
      Job `GitHub Release` при dispatch пропускается, поэтому глоб `dist/**/herdr-watch-*`
      сам workflow не проверил — сверен вручную по скачанным артефактам: оба файла
      (`herdr-watch-statusline-<ref>-{macos-arm64,linux-amd64}`) под него попадают.
      **Побочный эффект dispatch:** job `Docker image (GHCR)` не ограничена тегами и
      пушит `:${{ github.ref_name }}` и `:latest` — этот прогон переставил `:latest` на
      сборку с `main` вместо последнего релиза (v0.0.10).

## 10. Close the change

- [x] 10.1 `openspec-sync-specs` — fold the delta into `openspec/specs/claude-usage/spec.md`
- [x] 10.2 Confirm the acceptance rules (§4) now appear as requirements; they existed only in code before this change
      — «Ignore a reading that is incomplete or has fallen behind» встала в
      `openspec/specs/claude-usage/spec.md` шестью сценариями; до этого правила жили
      только в коде хука.
- [x] 10.3 `openspec-archive-change`
      — архивирован как `2026-08-29-render-statusline-directly`. Открытыми осознанно
      оставлены 7.2 (живой корпус payload'ов — нужен час обычной работы; синтетика
      покрывает все детерминированные ветки) и 9.3 (`workflow_dispatch` dry-run релиза
      до первого тега).

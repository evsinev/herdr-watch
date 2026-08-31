# herdr-watch — macOS menu-bar tray

A tiny native macOS agent that lives in the menu bar and mirrors the fleet state from a
running [herdr-watch](../README.md) server over HTTP + SSE.

- **Menu-bar item = Claude quota bars** — mini vertical bars, one per reported window:
  `5h`, `7d`, then the per-model weekly windows (`fable`, `opus`, … — max two in the menu
  bar, all of them in the menu). Fill height = utilization, color = the 70 % / 90 % bands
  shared with the UI. A window that isn't reported is **omitted**, never drawn as 0 %; a
  stale reading is dimmed. The fleet's attention level is carried by the counter next to
  them (`⛔1 ✅2`).
- **No quota reported → the icon-symbol fallback**, colored by fleet state (level-based):
  `blocked` → **red**, else `done` → **blue**, else a **monochrome** icon that auto-adapts
  to the menu-bar theme (white on dark, black on light) via `NSImage.isTemplate`. Without
  it an unreported quota would mean an empty — i.e. vanished — menu-bar item.
- **Click** → dropdown menu: one row per agent (colored dot + `host  title  [status]`,
  blocked on top), then the quota block (`claude · account` + source/age header and a
  `5h  34%  2h 34m` row per window), plus **Open dashboard…**, **Settings…**, **Quit**.
- **Settings**: server URL (default `http://localhost:8080`) and **Launch at Login**.

Status colors/priorities are kept in sync with `frontend/src/lib/theme.ts`; the quota
bands (70 / 90 %) with `USAGE_BANDS` there and `usage/UsageSeverity.java` on the backend.

## Requirements

- macOS 13+
- Swift toolchain (Xcode or Command Line Tools) — `swift --version`

## Build & run

```bash
cd tray-macos
swift build -c release
.build/release/HerdrWatchTray          # icon appears in the menu bar (no Dock icon)
```

Or build a proper `.app` bundle (needed for Launch-at-Login):

```bash
cd tray-macos
./scripts/make-app.sh                  # → HerdrWatchTray.app
open HerdrWatchTray.app
```

## Notes

- The server's SSE endpoint (`/api/stream`) has **no authentication** — if you point the
  tray at a remote server, the fleet status travels unauthenticated over the network.
- The per-model bars (`fable`, `opus`, …) exist only when the server publishes them —
  i.e. the account API source (`claude-usage.source: pull|auto`). Under the default
  `push` (statusline hook) there are two bars: `5h` and `7d`.
- The quota is fetched over REST (`/api/claude-usage`) on every (re)connect: the SSE
  `claude_usage` event only fires when the numbers move, so without it the bars would be
  missing until the next change.
- Bars are a colored (non-template) image, so they don't auto-invert with the menu-bar
  theme — the tray resolves the track color from the status item's `effectiveAppearance`
  and re-renders on `AppleInterfaceThemeChangedNotification`.
- Menu info rows (agents, quota) are drawn by a custom `MenuRowView` instead of a standard
  item. Both alternatives fail: a *disabled* item is inert but AppKit dims it on top of
  whatever `foregroundColor` the `attributedTitle` sets — barely legible on a dark menu,
  and no color fixes it — while an *enabled* item is legible but highlights under the
  cursor and so lies about being clickable. The custom view separates the two: our color,
  no highlight. Its metrics are measured off a real menu (title inset 20.5 pt, row height
  22 pt) so the columns line up with the action items.
- Row color is an explicit per-theme gray (0.65 white on dark, 0.38 on light), not
  `labelColor.withAlphaComponent(…)`: `labelColor` is already ~85 % white in dark mode, so
  the alpha compounded and brightness steps came out invisible. The muted gray (0.42 / 0.58)
  is left for what it actually means: an unreachable host, a stale quota reading.
- No server heartbeat: the client reconnects with backoff and re-applies the fresh
  `snapshot` the server sends on every (re)connect.
- Launch-at-Login uses `SMAppService` and only works from the packaged `.app`, not from a
  bare `swift run` binary.

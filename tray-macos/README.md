# herdr-watch — macOS menu-bar tray

A tiny native macOS agent that lives in the menu bar and mirrors the fleet state from a
running [herdr-watch](../README.md) server over HTTP + SSE.

- **Icon color = fleet state** (level-based):
  - an agent needs input (`blocked`) → **red** icon;
  - otherwise a task finished (`done`) → **blue** icon;
  - otherwise → **monochrome** icon that auto-adapts to the menu-bar theme (white on dark,
    black on light) via `NSImage.isTemplate`.
- **Click** → dropdown menu: one row per agent (colored dot + `host  title  [status]`,
  blocked on top), plus **Open dashboard…**, **Settings…**, **Quit**.
- **Settings**: server URL (default `http://localhost:8080`) and **Launch at Login**.

Status colors/priorities are kept in sync with `frontend/src/lib/theme.ts`.

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
- No server heartbeat: the client reconnects with backoff and re-applies the fresh
  `snapshot` the server sends on every (re)connect.
- Launch-at-Login uses `SMAppService` and only works from the packaged `.app`, not from a
  bare `swift run` binary.

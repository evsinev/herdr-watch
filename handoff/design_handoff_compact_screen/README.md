# Handoff: Compact fleet screen (herdr-watch)

## Overview
A new, stripped-down third view for **herdr-watch** (a read-only dashboard that aggregates the state of multiple `herdr` servers — a terminal multiplexer for AI coding agents). The Compact screen is meant for **small displays (~7")** mounted near a desk: a grid of equal-size cards, one per agent, showing only the agent name, its host, and its state expressed purely through color. It's a glance-only screen — no interaction.

The two existing views (Monitor, Settings) are unchanged and out of scope for this handoff. This package covers **only the Compact view** and the nav tab that reaches it.

## About the Design Files
The file in this bundle (`Herdr Watch.dc.html`) is a **design reference created in HTML** — a prototype showing the intended look and behavior, not production code to copy directly. The task is to **recreate the Compact screen in the target codebase's existing environment** (React, Vue, SwiftUI, etc.), using its established components, theme tokens, and data layer. If no environment exists yet, pick the most appropriate framework and implement it there.

The prototype is built as a self-contained component; the Compact view lives inside the `renderVals()` logic (the `compactCards` array + `isCompact` flag) and the corresponding `<sc-if value="{{ isCompact }}">` block in the template.

## Fidelity
**High-fidelity.** Final colors, typography, spacing, card dimensions, and layout are all intentional and should be reproduced as specified below.

## Screens / Views

### Compact
- **Purpose:** At-a-glance fleet status on a small screen. The viewer should read the color field and instantly know where the fire is (red = blocked). No clicks, no detail drill-down.
- **Layout:**
  - Container padding: `18px 18px 48px`; `max-width: 1400px`; centered (`margin: 0 auto`).
  - CSS grid: `grid-template-columns: repeat(auto-fill, minmax(230px, 1fr))`, `gap: 14px`. Cards fill each row and are all equal width.
  - Every card is **exactly the same size**: `height: 128px`, full column width.
- **Card component (one per agent):**
  - Box: `height: 128px`, `border-radius: 10px`, `padding: 14px 16px`, `overflow: hidden`, flex column, `gap: 8px`.
  - **Background:** the agent's status color at **10% alpha**. **Border:** `1px solid` the status color at **28% alpha**. (This colored fill/border IS the status signal — there is no dot, no badge, no status word.)
  - **Host line (top):** the host id (e.g. `m3-local`, `dqa1`), `JetBrains Mono`, `12px`, color `#7a808a`, `flex-shrink: 0`.
  - **Agent name:** `JetBrains Mono`, `16px`, `font-weight: 700`, `line-height: 1.3`, color = **the full-strength status color**, `word-break: break-all`, clamped to 3 lines (`display: -webkit-box; -webkit-line-clamp: 3; -webkit-box-orient: vertical; overflow: hidden`).
  - **Unreachable host:** whole card at `opacity: 0.6`.
- **Sort order:** cards are flattened across all hosts/workspaces and sorted by status priority, most urgent first: **blocked > working > done > idle > unknown**.

### Nav tab
Add a **Compact** tab to the existing top nav, between **Monitor** and **Settings**.
- Tab label: `Compact`, `IBM Plex Sans`, `13px`, `font-weight: 500`.
- Active tab: text `#e6e8ec`, `border-bottom: 2px solid #378ADD`.
- Inactive tab: text `#7a808a`, `border-bottom: 2px solid transparent`.
- Clicking sets the active view to `compact`.

## Interactions & Behavior
- The Compact view itself is **read-only** — no click handlers on cards.
- Only interaction is the nav tab switching the active view. No animations required.
- Responsive: the `auto-fill` grid reflows column count with width; on a 7" screen (~1024×600) expect roughly 3–4 columns. Cards never change size — only how many fit per row.

## State Management
- A single top-level `view` state: `'monitor' | 'compact' | 'settings'`. Compact adds the `'compact'` value.
- `compactCards` is **derived** (not stored): flatten every agent across all hosts → workspaces → agents, attach `{ host, title, statusColor, opacity }`, then sort by status priority descending.
- Source data is the same host/agent data that already drives the Monitor view — Compact adds no new data requirements.

## Design Tokens
Status colors (exact):
- blocked `#E24B4A`
- working `#EF9F27`
- done `#378ADD`
- idle `#639922`
- unknown `#888780`

Status priority (for sort): `blocked 5 > working 4 > done 3 > idle 2 > unknown 1`.

Derived per-status card styling: background = status color @ 10% alpha, border = status color @ 28% alpha, name text = status color @ 100%.

Neutrals / chrome:
- page background `#0b0d10`
- muted text (host line) `#7a808a`
- active nav / accent `#378ADD`
- active nav text `#e6e8ec`

Type:
- Monospace (ids, hosts, agent names): `JetBrains Mono` — host `12px/400`, agent name `16px/700`.
- Sans (nav labels): `IBM Plex Sans` — `13px/500`.

Geometry:
- card `border-radius: 10px`, `height: 128px`, `padding: 14px 16px`
- grid `gap: 14px`, columns `minmax(230px, 1fr)` auto-fill
- container padding `18px 18px 48px`, `max-width: 1400px`

## Assets
None. No images or icons — the status color field is the only visual signal. Fonts are Google Fonts (`JetBrains Mono`, `IBM Plex Sans`); use the codebase's existing equivalents if it already ships a mono/sans pair.

## Screenshots
- `screenshots/compact-screen.png` — the Compact view as designed (reference render).

## Files
- `Herdr Watch.dc.html` — full prototype (all three views). For the Compact screen, look at:
  - the `Compact` nav tab in the top nav bar,
  - the `<sc-if value="{{ isCompact }}">` block in the template,
  - `compactCards` / `isCompact` / `goCompact` in the `renderVals()` and handler methods of the logic class.

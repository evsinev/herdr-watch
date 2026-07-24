# Kickoff prompt for Claude Code

Paste this as your first message to Claude Code, with this folder as the working
directory.

---

I'm building **herdr-watch**, a read-only web dashboard that aggregates the live
state of several `herdr` servers (a terminal multiplexer for AI coding agents)
into one monitor — one local machine plus remote servers over SSH.

Everything you need is in this folder. **Start by reading
`CLAUDE_CODE_INSTRUCTIONS.md` in full** — it's the complete spec: architecture,
the real herdr JSON schemas, exact status colors, what's already built, and
what you need to finish. Then look at `design/design-bundle.html` (open it to
see the approved dark-theme UI), `design/design-template.html` and
`design/design-logic.js` (its exact structure, colors, sort order, and
validation), and `reference/dashboard-mock.jsx` (the SSE wiring and JSON shape).

The `backend/` folder is a **working Quarkus 3.15 / Java 21 skeleton that
already compiles** — the SSH polling, Registry, SSE stream, and read-only
`/api/servers` are done. Do not rewrite it; extend it.

Please work in this order:

1. Run `cd backend && mvn quarkus:dev` to confirm the backend builds and starts.
2. Backend: add host CRUD (`POST`/`PUT`/`DELETE /api/servers`) with a writable
   JSON state file layered over the read-only `application.yaml` bootstrap, plus
   **hot (re)connect** — adding/editing/removing a host starts or stops its
   `SshSource` in a virtual thread with no restart. Expose per-host health in
   `/api/servers`. Keep the Source abstraction and the one-ssh-connection-per-host
   model exactly as they are.
3. Frontend: scaffold a **Vite + React + TypeScript + shadcn/ui + Tailwind** app
   that matches the approved mockup closely (dark theme, IBM Plex Sans +
   JetBrains Mono, the exact status colors). Build the **Monitor** view (host →
   workspace → agent → nested worktree, health badges, dimmed unreachable cards,
   priority sorting) and the **Settings** view (host table + add/edit/remove
   modal with inline validation). Drive everything from a single
   `EventSource("/api/stream")` plus `/api/servers` for CRUD — no hardcoded data.
4. Add a top-level `README.md` and make the whole thing run with two commands
   (`mvn quarkus:dev` and `npm run dev`, frontend proxying `/api` to `:8080`).

Confirm each step against the acceptance criteria at the end of
`CLAUDE_CODE_INSTRUCTIONS.md` before moving on. Ask me if anything in the spec
is ambiguous rather than guessing on the data schemas — those are verified and
exact.

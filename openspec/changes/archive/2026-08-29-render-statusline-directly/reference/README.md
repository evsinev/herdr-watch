# reference/

`statusline.py` — the renderer this change ports, captured verbatim from
`~/.claude/statusline.py` on the reference machine at the time of the proposal.

It is **never executed** by anything in this repository. It lives here because the
file it was copied from is personal, unversioned and exists on a single laptop:
without it, `statusline-rust/src/render.rs` would be a 230-line port whose source
no reviewer can see. Once the change is archived this copy is the only record of
what the rendered line used to be, and it is what the cutover A/B in `tasks.md §6`
compares against.

Do not edit it. Behaviour changes belong in `statusline-rust/src/render.rs`.

//! Claude Code status line for herdr-watch.
//!
//! One command does two things with the same payload: it renders the operator's
//! status line, and it records the Claude subscription quota into the state file
//! `~/.config/herdr-watch/claude-usage.json` that the herdr-watch backend polls.
//!
//! The behaviour contract is `openspec/specs/claude-usage/spec.md`; the numbered
//! decisions cited throughout (D1…D10) are in
//! `openspec/changes/render-statusline-directly/design.md`.
//!
//! The overriding rule (design D2) is that this runs on the interactive path of
//! another program: it must always print a line, always exit 0, and never write a
//! byte to stderr — whatever goes wrong.

// Design D2: a panic here is a blank status line with no diagnostic, so the
// crate is not allowed to contain the constructs that produce one.
#![deny(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing,
    clippy::unreachable,
    clippy::todo,
    clippy::print_stderr
)]

pub mod args;
pub mod capture;
pub mod fmt;
pub mod json;
pub mod render;
pub mod round;
pub mod store;
pub mod transcript;

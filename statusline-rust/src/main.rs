//! Claude Code status line for herdr-watch — the wiring.
//!
//! Everything of substance lives in the modules; this file only decides the order
//! and guarantees the two rules that make the command safe to put on an editor's
//! interactive path (design D2):
//!
//!   * a line always reaches stdout, and it is the only thing that does;
//!   * the exit status is always 0, and stderr is always empty.
//!
//! Design D3: render **before** capture, from a single parse of the payload. If the
//! process is killed mid-write the line has already been drawn.

#![deny(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing,
    clippy::unreachable,
    clippy::todo,
    clippy::print_stderr
)]

use std::io::{IsTerminal, Read, Write};
use std::panic::{self, AssertUnwindSafe};

use serde_json::Value;

use herdr_watch_statusline::args::{self, Args};
use herdr_watch_statusline::capture::{Decision, decide};
use herdr_watch_statusline::fmt::Palette;
use herdr_watch_statusline::render::{Context, render};
use herdr_watch_statusline::store;

fn main() {
    // First statement, before anything can fail: the default hook writes
    // "thread 'main' panicked at …" to stderr, which the editor would surface.
    panic::set_hook(Box::new(|_| {}));

    let args = args::from_env();
    // Both meta-modes answer about the command itself, so neither reads stdin: the
    // operator running `--help` in a terminal must not have it hang on an open pipe.
    if args.show_help {
        // Colour for the usage text alone also depends on stdout being a terminal:
        // its reader is a person, and a piped `--help` should not be full of escape
        // sequences. The status line itself stays coloured through a pipe, because
        // its reader is Claude Code.
        let coloured = args.coloured && std::io::stdout().is_terminal();
        write_line(args::usage(coloured).trim_end());
        return;
    }
    if args.show_version {
        write_line(&format!("herdr-watch-statusline {}", args::VERSION));
        return;
    }

    let payload = read_payload();

    if args.render {
        // A panic while rendering costs the line, not the recording.
        let line = panic::catch_unwind(AssertUnwindSafe(|| render_line(&payload, &args)))
            .unwrap_or_default();
        write_line(&line);
    }

    if args.capture {
        // A panic while recording costs the recording, not the line.
        let _ = panic::catch_unwind(AssertUnwindSafe(|| capture(&payload, &args)));
    }

    // The exit status is always 0: there is no wrapped command whose status we
    // could adopt, and a non-zero one would only make the status line look broken.
}

/// Stdin as a payload. Unreadable, empty and invalid input all become an empty
/// object, so every element downstream simply finds no data.
fn read_payload() -> Value {
    let mut buffer = Vec::new();
    if std::io::stdin().read_to_end(&mut buffer).is_err() {
        return Value::Object(serde_json::Map::new());
    }
    match serde_json::from_slice::<Value>(&buffer) {
        Ok(value) if value.is_object() => value,
        _ => Value::Object(serde_json::Map::new()),
    }
}

fn render_line(payload: &Value, args: &Args) -> String {
    render(
        payload,
        &Context {
            palette: Palette::new(args.coloured, args.thresholds),
            now: store::now_secs() as f64,
            transcript_fallback: args.transcript_fallback,
        },
    )
}

fn capture(payload: &Value, args: &Args) {
    let path = store::state_file(args.state_file.as_deref());
    let existing = store::read_existing(&path);
    if let Decision::Write(record) = decide(payload, existing.as_ref(), store::now_secs()) {
        // Best effort: an unwritable path must not cost the operator their line.
        let _ = store::write_atomically(&path, &record);
    }
}

/// `write_all`, never `println!` — `println!` **panics** on a closed pipe, and a
/// failed write is not something we may report.
fn write_line(line: &str) {
    let stdout = std::io::stdout();
    let mut out = stdout.lock();
    let _ = out.write_all(line.as_bytes());
    let _ = out.write_all(b"\n");
    let _ = out.flush();
}

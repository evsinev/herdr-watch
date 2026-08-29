//! Command-line arguments (design D9).
//!
//! Hand-rolled, because the grammar is a `match` and a dependency that runs on every
//! status line refresh has to earn its place.
//!
//! **An unrecognised argument is ignored, never an error.** That is design D2
//! applied to `argv`: a typo in `settings.json`, or a future Claude Code that passes
//! an argument of its own, must not blank the operator's status line.

use std::env;

use crate::fmt::{BOLD, CYAN, DIM, Thresholds, paint};

pub const VERSION: &str = env!("CARGO_PKG_VERSION");

/// One documented option: short spelling (empty when there is none), long spelling,
/// and its description, one entry per rendered line.
///
/// A table rather than a hand-aligned block, so the columns are computed — inserting
/// colour codes into pre-aligned text would silently shift every description.
type Opt = (&'static str, &'static str, &'static [&'static str]);

const OPTIONS: &[Opt] = &[
    ("", "--no-capture", &["Render only; the state file is not touched."]),
    ("", "--capture-only", &["Record only; nothing is written to stdout."]),
    (
        "",
        "--transcript-fallback",
        &[
            "When the payload does not report context consumption,",
            "estimate it from the tail of the session transcript.",
            "Off by default: this is the one thing besides stdin",
            "that would be read.",
        ],
    ),
    ("", "--warn-at <PCT>", &["Where a figure turns yellow.  [default: 60]"]),
    (
        "",
        "--critical-at <PCT>",
        &[
            "Where a figure turns red.     [default: 85]",
            "Use 70 and 90 to match the dashboard's own bands.",
        ],
    ),
    (
        "",
        "--state-file <PATH>",
        &[
            "Where the quota is recorded.",
            "[env: HERDR_WATCH_USAGE_FILE]",
            "[default: ~/.config/herdr-watch/claude-usage.json]",
        ],
    ),
    ("", "--no-color", &["No ANSI codes. NO_COLOR is honoured too."]),
    ("-V", "--version", &["Print the version. stdin is not read."]),
    ("-h", "--help", &["Print this text. stdin is not read."]),
];

/// Where descriptions start, in columns.
const DESCRIPTION_COLUMN: usize = 31;

/// The usage text, optionally coloured.
///
/// It goes to **stdout**, like everything else this binary prints: stderr stays empty
/// unconditionally (design D2), so a status line somehow invoked with `--help` still
/// cannot spill a diagnostic into the operator's terminal.
///
/// Colour is decided by the caller, and for the usage text alone it also depends on
/// stdout being a terminal. The rendered status line is coloured unconditionally
/// because its consumer is Claude Code, which interprets the codes from a pipe; this
/// text's consumer is a person, and piping it into a file or a pager should not fill
/// them with escape sequences.
pub fn usage(coloured: bool) -> String {
    let head = |text: &str| paint(text, &[BOLD], coloured);
    let flag = |text: &str| paint(text, &[CYAN], coloured);

    let mut out = String::with_capacity(2048);

    out.push_str(&head("herdr-watch-statusline"));
    out.push(' ');
    out.push_str(&paint(VERSION, &[DIM], coloured));
    out.push_str(
        "
The Claude Code status line for herdr-watch.

Configure it as your `statusLine` command in ~/.claude/settings.json. It reads the
payload Claude Code puts on stdin, writes one rendered line to stdout, and records the
Claude quota into ~/.config/herdr-watch/claude-usage.json for the herdr-watch backend.

It reads its input and nothing else: no network, no credential, no subprocess.

",
    );

    out.push_str(&head("USAGE:"));
    out.push_str("\n    ");
    out.push_str(&flag("herdr-watch-statusline"));
    out.push_str(" [OPTIONS] < payload.json\n\n");

    out.push_str(&head("OPTIONS:"));
    out.push('\n');
    for (short, long, description) in OPTIONS {
        let (indent, label) = if short.is_empty() {
            (8, flag(long))
        } else {
            (4, format!("{}, {}", flag(short), flag(long)))
        };
        let width = indent + short.len() + if short.is_empty() { 0 } else { 2 } + long.len();
        let padding = DESCRIPTION_COLUMN.saturating_sub(width).max(1);

        for (index, line) in description.iter().enumerate() {
            if index == 0 {
                out.push_str(&" ".repeat(indent));
                out.push_str(&label);
                out.push_str(&" ".repeat(padding));
            } else {
                out.push_str(&" ".repeat(DESCRIPTION_COLUMN));
            }
            out.push_str(&dim_brackets(line, coloured));
            out.push('\n');
        }
    }

    out.push_str(
        "
An unrecognised argument is ignored rather than refused: a typo here must not blank
your status line.

",
    );

    out.push_str(&head("EXAMPLES:"));
    out.push_str(
        "
    Compare the rendered line against another implementation:
        herdr-watch-statusline --no-capture < payload.json

    Keep your own renderer and record the quota alongside it:
        sh -c 'p=$(cat); printf %s \"$p\" | herdr-watch-statusline --capture-only;
               printf %s \"$p\" | ~/.claude/my-statusline.sh'
",
    );
    out
}

/// Dim the `[default: …]` / `[env: …]` notes so the description itself reads first.
fn dim_brackets(line: &str, coloured: bool) -> String {
    if !coloured {
        return line.to_owned();
    }
    match (line.find('['), line.rfind(']')) {
        (Some(open), Some(close)) if close > open => {
            let (before, rest) = line.split_at(open);
            let bracketed = rest.get(..close + 1 - open).unwrap_or(rest);
            let after = rest.get(close + 1 - open..).unwrap_or("");
            format!("{before}{}{after}", paint(bracketed, &[DIM], true))
        }
        _ => line.to_owned(),
    }
}

/// What this invocation should do.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Args {
    pub render: bool,
    pub capture: bool,
    pub transcript_fallback: bool,
    pub coloured: bool,
    pub thresholds: Thresholds,
    pub state_file: Option<String>,
    /// Print the version and stop, without reading stdin.
    pub show_version: bool,
    /// Print the usage text and stop, without reading stdin.
    pub show_help: bool,
}

impl Default for Args {
    fn default() -> Self {
        Self {
            render: true,
            capture: true,
            transcript_fallback: false,
            coloured: true,
            thresholds: Thresholds::default(),
            state_file: None,
            show_version: false,
            show_help: false,
        }
    }
}

/// Parse the process's own arguments, honouring `NO_COLOR`.
pub fn from_env() -> Args {
    let argv: Vec<String> = env::args().skip(1).collect();
    let no_color = env::var("NO_COLOR").is_ok_and(|v| !v.is_empty());
    parse(&argv, no_color)
}

pub fn parse(argv: &[String], no_color_env: bool) -> Args {
    let mut args = Args { coloured: !no_color_env, ..Args::default() };
    let mut index = 0;
    while index < argv.len() {
        let Some(current) = argv.get(index) else { break };
        match current.as_str() {
            "--no-capture" => args.capture = false,
            "--capture-only" => args.render = false,
            "--transcript-fallback" => args.transcript_fallback = true,
            "--no-color" | "--no-colour" => args.coloured = false,
            "-V" | "--version" => args.show_version = true,
            "-h" | "--help" => args.show_help = true,
            "--warn-at" => {
                if let Some(value) = percent_at(argv, index + 1) {
                    args.thresholds.warn_at = value;
                }
                index += 1;
            }
            "--critical-at" => {
                if let Some(value) = percent_at(argv, index + 1) {
                    args.thresholds.critical_at = value;
                }
                index += 1;
            }
            "--state-file" => {
                if let Some(value) = argv.get(index + 1).filter(|v| !v.is_empty()) {
                    args.state_file = Some(value.clone());
                }
                index += 1;
            }
            // Anything else — including a bare word and an unknown flag — is
            // ignored. See the module comment.
            _ => {}
        }
        index += 1;
    }
    args
}

/// A threshold value, or `None` so the default survives. A malformed or
/// out-of-range value is not worth failing over.
fn percent_at(argv: &[String], index: usize) -> Option<i64> {
    argv.get(index)?.parse::<i64>().ok().filter(|v| (0..=100).contains(v))
}

#[cfg(test)]
#[allow(clippy::unwrap_used, clippy::panic)]
mod tests {
    use super::*;

    fn parse_str(args: &[&str]) -> Args {
        parse(&args.iter().map(|s| (*s).to_owned()).collect::<Vec<_>>(), false)
    }

    #[test]
    fn the_default_invocation_renders_and_records_in_colour() {
        let args = parse_str(&[]);
        assert!(args.render && args.capture && args.coloured);
        assert!(!args.transcript_fallback, "the transcript is read only when asked for");
        assert_eq!(args.thresholds, Thresholds { warn_at: 60, critical_at: 85 });
        assert_eq!(args.state_file, None);
    }

    #[test]
    fn each_half_can_be_switched_off() {
        assert!(!parse_str(&["--no-capture"]).capture);
        assert!(parse_str(&["--no-capture"]).render);
        assert!(!parse_str(&["--capture-only"]).render);
        assert!(parse_str(&["--capture-only"]).capture);
    }

    #[test]
    fn thresholds_and_the_state_file_are_taken_from_arguments() {
        let args =
            parse_str(&["--warn-at", "70", "--critical-at", "90", "--state-file", "/tmp/u.json"]);
        assert_eq!(args.thresholds, Thresholds { warn_at: 70, critical_at: 90 });
        assert_eq!(args.state_file.as_deref(), Some("/tmp/u.json"));
    }

    #[test]
    fn a_malformed_threshold_falls_back_to_the_default_rather_than_failing() {
        for bad in [
            vec!["--warn-at", "banana"],
            vec!["--warn-at", "-5"],
            vec!["--warn-at", "101"],
            vec!["--warn-at"],
        ] {
            let args = parse_str(&bad);
            assert_eq!(args.thresholds.warn_at, 60, "{bad:?}");
            assert!(args.render && args.capture);
        }
    }

    #[test]
    fn an_unrecognised_argument_is_ignored_rather_than_refused() {
        // Design D9: a typo must not blank the status line.
        let args = parse_str(&["--wrap", "python3 ~/.claude/statusline.py", "--no-capture"]);
        assert!(args.render, "the default behaviour still runs");
        assert!(!args.capture, "and the arguments it does know still apply");
    }

    #[test]
    fn a_value_that_looks_like_a_flag_is_still_consumed_as_a_value() {
        // `--warn-at --no-capture` must not silently enable --no-capture.
        let args = parse_str(&["--warn-at", "--no-capture"]);
        assert_eq!(args.thresholds.warn_at, 60);
        assert!(args.capture, "the malformed value was consumed, not re-read as a flag");
    }

    #[test]
    fn colour_is_off_when_the_environment_says_so_or_the_flag_does() {
        assert!(!parse_str(&["--no-color"]).coloured);
        assert!(!parse(&[], true).coloured);
        assert!(parse(&[], false).coloured);
    }

    #[test]
    fn the_help_flag_is_recognised_in_both_spellings() {
        assert!(parse_str(&["-h"]).show_help);
        assert!(parse_str(&["--help"]).show_help);
        assert!(!parse_str(&[]).show_help);
    }

    #[test]
    fn colouring_the_usage_text_does_not_move_a_single_column() {
        // The whole reason OPTIONS is a table: escape codes are invisible but they
        // are bytes, so pre-aligned text would silently shift under them.
        let plain = usage(false);
        let coloured = usage(true);
        let stripped = strip_ansi(&coloured);

        assert_ne!(plain, coloured, "asking for colour must actually colour it");
        assert!(coloured.contains('\u{1b}'));
        assert!(!plain.contains('\u{1b}'), "plain output must carry no escape codes");
        assert_eq!(stripped, plain, "colour must not change one visible character");
    }

    fn strip_ansi(text: &str) -> String {
        let mut out = String::with_capacity(text.len());
        let mut chars = text.chars();
        while let Some(c) = chars.next() {
            if c != '\u{1b}' {
                out.push(c);
                continue;
            }
            // Skip "[<digits>m".
            for c in chars.by_ref() {
                if c == 'm' {
                    break;
                }
            }
        }
        out
    }

    #[test]
    fn the_usage_text_documents_every_flag_the_parser_accepts() {
        // Cheap guard against a flag being added and the help quietly going stale.
        let text = usage(false);
        for flag in [
            "--no-capture",
            "--capture-only",
            "--transcript-fallback",
            "--warn-at",
            "--critical-at",
            "--state-file",
            "--no-color",
            "--version",
            "--help",
        ] {
            assert!(text.contains(flag), "usage() does not mention {flag}");
        }
        assert!(text.contains(VERSION), "usage() must name the version it belongs to");
        assert!(text.contains("HERDR_WATCH_USAGE_FILE"));
        assert!(text.contains("NO_COLOR"));
    }

    #[test]
    fn the_version_flag_is_recognised_in_both_spellings() {
        assert!(parse_str(&["-V"]).show_version);
        assert!(parse_str(&["--version"]).show_version);
        assert!(!parse_str(&[]).show_version);
    }
}

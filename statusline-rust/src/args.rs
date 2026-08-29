//! Command-line arguments (design D9).
//!
//! Hand-rolled, because the grammar is a `match` and a dependency that runs on every
//! status line refresh has to earn its place.
//!
//! **An unrecognised argument is ignored, never an error.** That is design D2
//! applied to `argv`: a typo in `settings.json`, or a future Claude Code that passes
//! an argument of its own, must not blank the operator's status line.

use std::env;

use crate::fmt::Thresholds;

pub const VERSION: &str = env!("CARGO_PKG_VERSION");

/// The usage text.
///
/// It goes to **stdout**, like everything else this binary prints: stderr stays empty
/// unconditionally (design D2), so a status line somehow invoked with `--help` still
/// cannot spill a diagnostic into the operator's terminal.
pub fn usage() -> String {
    format!(
        "\
herdr-watch-statusline {VERSION}
The Claude Code status line for herdr-watch.

Configure it as your `statusLine` command in ~/.claude/settings.json. It reads the
payload Claude Code puts on stdin, writes one rendered line to stdout, and records the
Claude quota into ~/.config/herdr-watch/claude-usage.json for the herdr-watch backend.

It reads its input and nothing else: no network, no credential, no subprocess.

USAGE:
    herdr-watch-statusline [OPTIONS] < payload.json

OPTIONS:
        --no-capture           Render only; the state file is not touched.
        --capture-only         Record only; nothing is written to stdout.
        --transcript-fallback  When the payload does not report context consumption,
                               estimate it from the tail of the session transcript.
                               Off by default: this is the one thing besides stdin
                               that would be read.
        --warn-at <PCT>        Where a figure turns yellow.  [default: 60]
        --critical-at <PCT>    Where a figure turns red.     [default: 85]
                               Use 70 and 90 to match the dashboard's own bands.
        --state-file <PATH>    Where the quota is recorded.
                               [env: HERDR_WATCH_USAGE_FILE]
                               [default: ~/.config/herdr-watch/claude-usage.json]
        --no-color             No ANSI codes. NO_COLOR is honoured too.
    -V, --version              Print the version. stdin is not read.
    -h, --help                 Print this text. stdin is not read.

An unrecognised argument is ignored rather than refused: a typo here must not blank
your status line.

EXAMPLES:
    Compare the rendered line against another implementation:
        herdr-watch-statusline --no-capture < payload.json

    Keep your own renderer and record the quota alongside it:
        sh -c 'p=$(cat); printf %s \"$p\" | herdr-watch-statusline --capture-only;
               printf %s \"$p\" | ~/.claude/my-statusline.sh'
"
    )
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
    fn the_usage_text_documents_every_flag_the_parser_accepts() {
        // Cheap guard against a flag being added and the help quietly going stale.
        let text = usage();
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

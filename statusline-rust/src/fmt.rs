//! Colours and the small formatters the line is built from.
//!
//! Ported from `openspec/changes/render-statusline-directly/reference/statusline.py`.

use crate::round::half_even;

pub const RESET: &str = "\x1b[0m";
pub const DIM: &str = "\x1b[2m";
pub const BOLD: &str = "\x1b[1m";
pub const CYAN: &str = "\x1b[36m";
pub const YELLOW: &str = "\x1b[33m";
pub const GREEN: &str = "\x1b[32m";
pub const RED: &str = "\x1b[31m";

/// Cells in the context bar.
pub const BAR_CELLS: i64 = 10;

/// Where the severity scale changes colour. Configurable by the operator
/// (`--warn-at` / `--critical-at`); the defaults are what the ported renderer used.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Thresholds {
    pub warn_at: i64,
    pub critical_at: i64,
}

impl Default for Thresholds {
    fn default() -> Self {
        Self { warn_at: 60, critical_at: 85 }
    }
}

/// A palette that can be turned off wholesale (`--no-color`, `NO_COLOR`).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Palette {
    coloured: bool,
    thresholds: Thresholds,
}

impl Palette {
    pub fn new(coloured: bool, thresholds: Thresholds) -> Self {
        Self { coloured, thresholds }
    }

    pub fn plain() -> Self {
        Self::new(false, Thresholds::default())
    }

    /// Wrap `text` in the given codes. With colour off the codes vanish and the
    /// line is the same text, unstyled.
    pub fn paint(&self, text: &str, codes: &[&str]) -> String {
        paint(text, codes, self.coloured)
    }

    /// The shared severity colour: context and each quota window are coloured the
    /// same way, so one figure never means two things.
    pub fn pressure(&self, percent: i64) -> &'static str {
        if percent >= self.thresholds.critical_at {
            RED
        } else if percent >= self.thresholds.warn_at {
            YELLOW
        } else {
            GREEN
        }
    }
}

/// Wrap `text` in the given codes, or return it untouched when colour is off.
///
/// The one painting primitive in the crate: the status line goes through
/// [`Palette`], the usage text calls this directly (it has no severity to colour by).
pub fn paint(text: &str, codes: &[&str], coloured: bool) -> String {
    if !coloured {
        return text.to_owned();
    }
    let mut out = String::with_capacity(text.len() + 16);
    for code in codes {
        out.push_str(code);
    }
    out.push_str(text);
    out.push_str(RESET);
    out
}

/// Token counts: `1.0M`, `1.5k`, or a plain integer.
pub fn fmt_num(value: f64) -> String {
    if value >= 1e6 {
        format!("{:.1}M", value / 1e6)
    } else if value >= 1e3 {
        format!("{:.1}k", value / 1e3)
    } else {
        format!("{}", value as i64)
    }
}

/// A duration: `5h01m`, `43m`, or `18s`.
pub fn fmt_span(seconds: f64) -> String {
    let seconds = seconds as i64;
    if seconds >= 3600 {
        format!("{}h{:02}m", seconds / 3600, (seconds % 3600) / 60)
    } else if seconds >= 60 {
        format!("{}m", seconds / 60)
    } else {
        format!("{seconds}s")
    }
}

/// Time left before a limit resets: `2h13m` or `47m`.
///
/// Never below `1m`: while the reset is still ahead, "0m" misleads.
pub fn fmt_left(seconds: f64) -> String {
    let mut minutes = half_even(seconds / 60.0) as i64;
    if minutes < 1 {
        minutes = 1;
    }
    let (hours, minutes) = (minutes / 60, minutes % 60);
    if hours > 0 { format!("{hours}h{minutes:02}m") } else { format!("{minutes}m") }
}

#[cfg(test)]
#[allow(clippy::unwrap_used, clippy::panic)]
mod tests {
    use super::*;

    #[test]
    fn numbers_switch_units_at_the_thousand_and_the_million() {
        assert_eq!(fmt_num(0.0), "0");
        assert_eq!(fmt_num(999.0), "999");
        assert_eq!(fmt_num(999.4), "999");
        assert_eq!(fmt_num(1000.0), "1.0k");
        assert_eq!(fmt_num(185_300.0), "185.3k");
        assert_eq!(fmt_num(999_949.0), "999.9k");
        assert_eq!(fmt_num(1_000_000.0), "1.0M");
        assert_eq!(fmt_num(1_050_000.0), "1.1M");
    }

    #[test]
    fn spans_drop_seconds_after_a_minute_and_minutes_never_after_an_hour() {
        assert_eq!(fmt_span(18.0), "18s");
        assert_eq!(fmt_span(59.9), "59s");
        assert_eq!(fmt_span(60.0), "1m");
        assert_eq!(fmt_span(2580.0), "43m");
        assert_eq!(fmt_span(3600.0), "1h00m");
        assert_eq!(fmt_span(18060.0), "5h01m");
    }

    #[test]
    fn time_left_never_reads_as_zero_while_the_reset_is_ahead() {
        assert_eq!(fmt_left(1.0), "1m");
        assert_eq!(fmt_left(29.0), "1m");
        assert_eq!(fmt_left(2820.0), "47m");
        assert_eq!(fmt_left(7980.0), "2h13m");
        assert_eq!(fmt_left(3660.0), "1h01m");
    }

    #[test]
    fn the_severity_scale_follows_the_configured_thresholds() {
        let default = Palette::new(true, Thresholds::default());
        assert_eq!(default.pressure(59), GREEN);
        assert_eq!(default.pressure(60), YELLOW);
        assert_eq!(default.pressure(84), YELLOW);
        assert_eq!(default.pressure(85), RED);

        let backend = Palette::new(true, Thresholds { warn_at: 70, critical_at: 90 });
        assert_eq!(backend.pressure(65), GREEN);
        assert_eq!(backend.pressure(70), YELLOW);
        assert_eq!(backend.pressure(90), RED);
    }

    #[test]
    fn colour_can_be_turned_off_entirely() {
        assert_eq!(Palette::plain().paint("5h 43%", &[RED, BOLD]), "5h 43%");
        assert_eq!(
            Palette::new(true, Thresholds::default()).paint("x", &[CYAN]),
            format!("{CYAN}x{RESET}")
        );
    }
}

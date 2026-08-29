//! Two rounding rules, on purpose (design D4).
//!
//! Three languages disagree about halves: Python's `round()` is half-to-even,
//! Rust's `f64::round()` is half-away-from-zero, Java's `Math.round` is half-up.
//! This crate writes for one of them and prints for another, so it needs both.

/// Percentages are recorded on a 0–100 scale.
pub const MAX_PERCENT: f64 = 100.0;

/// Half-up — the rule for **recorded** figures (design D4a).
///
/// `ClaudeUsageReader` re-rounds anything fractional it finds through
/// `usage/Percents.java` (`Math.round`, half-up). Writer and reader must not
/// diverge by convention rather than by data, or the push and pull sources
/// disagree for no reason anybody can find.
///
/// Negative values are rejected before they reach here, and on the non-negative
/// domain `f64::round()` is exactly `Math.round` — using it also sidesteps the
/// `0.49999999999999994` edge case that a literal `(x + 0.5).floor()` would
/// reproduce as a bug.
pub fn half_up(x: f64) -> f64 {
    x.round()
}

/// Half-to-even — the rule for **rendered** figures (design D4b).
///
/// Byte parity with the Python renderer being replaced is what makes the cutover
/// verifiable by `diff`. Without this the context bar differs by one cell whenever
/// `pct / 100 * 10` lands exactly on `x.5` — at 25 %, 45 %, 65 % and 85 %.
pub fn half_even(x: f64) -> f64 {
    let rounded = x.round();
    if (x - x.trunc()).abs() == 0.5 && rounded % 2.0 != 0.0 {
        rounded - x.signum()
    } else {
        rounded
    }
}

/// A utilization figure as a whole percent, or `None` when it is not a figure.
///
/// Mirrors both the Python hook's `window()` and Java's `Percents.toWhole`:
/// non-finite and negative are rejected, an overage is **clamped rather than
/// dropped** — an overage must not make the gauge vanish at the moment it matters
/// most.
pub fn record_percent(raw: f64) -> Option<i64> {
    if !raw.is_finite() || raw < 0.0 {
        return None;
    }
    Some(half_up(raw).min(MAX_PERCENT) as i64)
}

/// A utilization figure for display: clamped into 0–100, rounded half-to-even.
pub fn display_percent(raw: f64) -> i64 {
    if !raw.is_finite() {
        return 0;
    }
    half_even(raw).clamp(0.0, MAX_PERCENT) as i64
}

#[cfg(test)]
#[allow(clippy::unwrap_used, clippy::panic)]
mod tests {
    use super::*;

    #[test]
    fn live_fractional_noise_rounds_to_the_nearest_percent() {
        // Observed live: used_percentage arrives as a fraction × 100.
        assert_eq!(record_percent(7.000000000000001), Some(7));
        assert_eq!(record_percent(7.4), Some(7));
        assert_eq!(record_percent(7.6), Some(8));
        assert_eq!(record_percent(0.2), Some(0));
        assert_eq!(record_percent(99.5), Some(100));
    }

    #[test]
    fn recorded_halves_go_up_where_python_went_to_even() {
        // Design D4a: the deliberate divergence. Python wrote 2, Java would say 3.
        assert_eq!(record_percent(2.5), Some(3));
        assert_eq!(record_percent(4.5), Some(5));
        assert_eq!(record_percent(0.5), Some(1));
    }

    #[test]
    fn overage_is_clamped_not_dropped() {
        assert_eq!(record_percent(104.7), Some(100));
        assert_eq!(record_percent(100.0), Some(100));
    }

    #[test]
    fn nonsense_figures_are_rejected() {
        assert_eq!(record_percent(-1.0), None);
        assert_eq!(record_percent(-0.5), None);
        assert_eq!(record_percent(f64::NAN), None);
        assert_eq!(record_percent(f64::INFINITY), None);
    }

    #[test]
    fn half_up_is_never_below_half_even() {
        // Design D4a rests on this: a record written here after one written by the
        // Python hook can never be read as a step backwards. Assert it rather than
        // trusting the argument.
        let mut x = 0.0_f64;
        while x <= 100.0 {
            assert!(
                half_up(x) >= half_even(x),
                "half_up({x}) = {} < half_even({x}) = {}",
                half_up(x),
                half_even(x)
            );
            x += 0.1;
        }
        for x in [0.5, 1.5, 2.5, 3.5, 24.5, 45.5, 99.5] {
            assert!(half_up(x) >= half_even(x), "{x}");
        }
    }

    #[test]
    fn displayed_halves_go_to_even() {
        // Design D4b: byte parity with the renderer being replaced.
        assert_eq!(display_percent(2.5), 2);
        assert_eq!(display_percent(3.5), 4);
        assert_eq!(display_percent(0.5), 0);
        assert_eq!(display_percent(7.6), 8);
    }

    #[test]
    fn display_clamps_rather_than_rejecting() {
        assert_eq!(display_percent(-5.0), 0);
        assert_eq!(display_percent(140.0), 100);
        assert_eq!(display_percent(f64::NAN), 0);
    }
}

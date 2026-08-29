//! Which readings are accepted into the shared record, and when it is rewritten.
//!
//! This is the part that matters (design D5, D6). The state file is not private
//! storage: every Claude Code session on the machine writes it, those sessions may
//! be of **different versions**, there is no lock, and herdr-watch polls the file
//! by modification time. The rules below were arrived at from live misbehaviour and
//! each one exists because something broke without it.
//!
//! Ported from `scripts/herdr-watch-statusline-hook.py`.

use serde_json::Value;

use crate::json::{get, num, to_i64};
use crate::round::record_percent;

/// Both window names, in the order they are written.
pub const WINDOWS: [&str; 2] = ["five_hour", "seven_day"];

/// A validated window: a whole percent and a positive reset time.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Window {
    pub used_percent: i64,
    pub resets_at: i64,
}

/// A usable reading.
///
/// `five_hour` is a `Window`, **not** an `Option<Window>` (design D5). The rule
/// "a reading without a session window is not a reading" is thereby an invariant
/// of the type rather than a check a later edit can quietly drop.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Reading {
    pub five_hour: Window,
    pub seven_day: Option<Window>,
}

impl Reading {
    fn window(&self, name: &str) -> Option<Window> {
        match name {
            "five_hour" => Some(self.five_hour),
            "seven_day" => self.seven_day,
            _ => None,
        }
    }

    /// The record's bytes, serialized by hand (design D7).
    ///
    /// Three keys, all integers, in the Python writer's key order. The document is
    /// tiny and entirely under our control, so hand-writing it keeps "unchanged
    /// implies identical bytes" trivially true and keeps every float formatter off
    /// the path.
    pub fn to_record(&self, captured_at: i64) -> String {
        let mut out = String::with_capacity(160);
        out.push_str("{\"capturedAt\": ");
        out.push_str(&captured_at.to_string());
        for name in WINDOWS {
            if let Some(w) = self.window(name) {
                out.push_str(", \"");
                out.push_str(name);
                out.push_str("\": {\"used_percentage\": ");
                out.push_str(&w.used_percent.to_string());
                out.push_str(", \"resets_at\": ");
                out.push_str(&w.resets_at.to_string());
                out.push('}');
            }
        }
        out.push('}');
        out
    }
}

/// What to do with a reading, given what is already recorded.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Decision {
    /// Do not touch the file — not its contents and not its modification time.
    Leave,
    /// Replace the file with exactly these bytes.
    Write(String),
}

/// One validated window, or `None` — an unusable window is omitted, never zeroed.
///
/// `used_percentage` is on a 0–100 scale but is **not** always an integer: it is a
/// fraction × 100, so a value that is not whole arrives with floating-point noise
/// (observed live: `7.000000000000001`). Rejecting non-integers here is what used
/// to make the 5-hour bar come and go.
pub fn window(raw: &Value) -> Option<Window> {
    if !raw.is_object() {
        return None;
    }
    let used = record_percent(num(get(raw, "used_percentage"))?)?;
    let resets = to_i64(get(raw, "resets_at")).filter(|r| *r > 0)?;
    Some(Window { used_percent: used, resets_at: resets })
}

/// The windows of a usable reading, or `None`.
///
/// A reading MUST carry `five_hour` to count. That is not fussiness: several Claude
/// Code sessions of different versions share this one file, and older ones emit
/// `rate_limits` with only `seven_day`, whose value agrees neither with the account
/// figure nor with each other (observed live: 7 / 20 / 26 / 29 against a true 34).
/// Accepting those lets an old session overwrite a good record on every tick.
/// Requiring the window the old shape never has filters them out without tracking
/// versions.
pub fn windows_of(payload: &Value) -> Option<Reading> {
    let limits = get(payload, "rate_limits");
    if !limits.is_object() {
        return None;
    }
    Some(Reading {
        five_hour: window(get(limits, "five_hour"))?,
        seven_day: window(get(limits, "seven_day")),
    })
}

/// True when this reading is BEHIND the record — an older window, or a step back
/// inside one.
///
/// Observed live over 14 minutes on one machine: sessions of different versions
/// wrote 22 % / 6 % / 16 % / 33 % in turn, two of them carrying a `resets_at` five
/// hours stale — a window that had already reset. Requiring a usable `five_hour`
/// stops the bar from vanishing, but not the figures from flapping between sessions.
///
/// Two facts make a lagging reading recognisable without tracking versions:
/// utilization inside one window only ever grows, and a window's reset time only
/// ever moves forward. So a reading that goes backwards on either count is behind,
/// and the record it would overwrite is the better one.
///
/// Compared per window and only where both sides have it: a window the payload does
/// not carry says nothing about lag. A recorded window that cannot be read as
/// figures is skipped rather than protected — otherwise garbage in the file would
/// freeze it forever.
pub fn regresses(new: &Reading, existing: Option<&Value>) -> bool {
    let Some(existing) = existing else { return false };
    for name in WINDOWS {
        let Some(fresh) = new.window(name) else { continue };
        let recorded = get(existing, name);
        if !recorded.is_object() {
            continue;
        }
        let (Some(resets), Some(used)) =
            (num(get(recorded, "resets_at")), num(get(recorded, "used_percentage")))
        else {
            continue; // the record is corrupt — there is nothing to compare against
        };
        let fresh_resets = fresh.resets_at as f64;
        if fresh_resets < resets {
            return true; // a window that has already reset
        }
        if fresh_resets == resets && (fresh.used_percent as f64) < used {
            return true; // utilization does not decrease inside one window
        }
    }
    false
}

/// True when the recorded figures already equal these ones.
///
/// Design D6. Python compared raw dicts, where `27 == 27.0` and an extra key means
/// "changed"; here `Value::Number(27) != Value::Number(27.0)` because `PartialEq`
/// compares representation. Comparing `Value`s directly would report "changed" on
/// every tick against a file some other writer produced — rewriting it, moving the
/// modification time, and turning `capturedAt` into "when the command last ran".
/// That would kill the staleness indicator the whole feature rests on, while
/// looking like everything works.
pub fn unchanged(new: &Reading, existing: Option<&Value>) -> bool {
    let Some(existing) = existing else { return false };
    if !existing.is_object() {
        return false;
    }
    // Without a capture time the record is useless — rewrite it. A float is not an
    // integer here, matching Python's `isinstance(x, int)`.
    match get(existing, "capturedAt") {
        Value::Number(n) if n.is_i64() || n.is_u64() => {}
        _ => return false,
    }
    WINDOWS.iter().all(|name| window_matches(get(existing, name), new.window(name)))
}

fn window_matches(recorded: &Value, fresh: Option<Window>) -> bool {
    match (recorded, fresh) {
        (Value::Null, None) => true,
        (Value::Object(map), Some(w)) => {
            // Exactly two keys: an extra key counts as a change, as in Python.
            map.len() == 2
                && num(get(recorded, "used_percentage")) == Some(w.used_percent as f64)
                && num(get(recorded, "resets_at")) == Some(w.resets_at as f64)
        }
        _ => false,
    }
}

/// The whole capture decision, as a pure function of the payload, the record and
/// the clock. The write path has no branches of its own.
pub fn decide(payload: &Value, existing: Option<&Value>, now: i64) -> Decision {
    let Some(new) = windows_of(payload) else {
        // No usable reading — leave the previous record alone. Normal before a
        // session's first API response, and the anti-old-client rule besides.
        return Decision::Leave;
    };
    if regresses(&new, existing) {
        // A lagging session: the window has already reset, or the figure stepped
        // back. Do not touch the record at all, or the numbers flap on every tick.
        return Decision::Leave;
    }
    if unchanged(&new, existing) {
        // Same figures — do not touch the file, not even its modification time.
        // `capturedAt` must mean "when the figures last moved": under
        // `statusLine.refreshInterval` this command is re-run on a timer with no API
        // call in between, so re-stamping would pass hour-old numbers off as current
        // and the staleness indicator would die.
        return Decision::Leave;
    }
    Decision::Write(new.to_record(now))
}

#[cfg(test)]
#[allow(clippy::unwrap_used, clippy::panic, clippy::indexing_slicing)]
mod tests {
    use super::*;
    use serde_json::json;

    fn reading(five: (i64, i64), seven: Option<(i64, i64)>) -> Reading {
        Reading {
            five_hour: Window { used_percent: five.0, resets_at: five.1 },
            seven_day: seven.map(|(u, r)| Window { used_percent: u, resets_at: r }),
        }
    }

    fn payload(five: Value, seven: Value) -> Value {
        let mut limits = serde_json::Map::new();
        if !five.is_null() {
            limits.insert("five_hour".into(), five);
        }
        if !seven.is_null() {
            limits.insert("seven_day".into(), seven);
        }
        json!({ "rate_limits": limits })
    }

    // ── windows_of ───────────────────────────────────────────────────────────

    #[test]
    fn both_windows_are_read() {
        let p = payload(
            json!({"used_percentage": 27, "resets_at": 1787803200}),
            json!({"used_percentage": 24, "resets_at": 1788206400}),
        );
        assert_eq!(windows_of(&p), Some(reading((27, 1787803200), Some((24, 1788206400)))));
    }

    #[test]
    fn one_window_only_the_other_is_absent_not_zero() {
        let p = payload(json!({"used_percentage": 27, "resets_at": 1787803200}), Value::Null);
        let r = windows_of(&p).unwrap();
        assert_eq!(r.seven_day, None);
        assert!(!r.to_record(1).contains("seven_day"));
    }

    #[test]
    fn incomplete_five_hour_makes_the_whole_reading_unusable() {
        // The anti-old-client rule: a valid seven_day cannot rescue it.
        let p = payload(
            json!({"used_percentage": 27}),
            json!({"used_percentage": 24, "resets_at": 1788206400}),
        );
        assert_eq!(windows_of(&p), None);
    }

    #[test]
    fn old_client_shape_carrying_only_the_weekly_window_is_ignored() {
        let p = json!({
            "session_id": "s", "version": "2.1.245",
            "rate_limits": {"seven_day": {"used_percentage": 7, "resets_at": 1788206400}}
        });
        assert_eq!(windows_of(&p), None);
    }

    #[test]
    fn a_payload_without_rate_limits_is_not_a_reading() {
        assert_eq!(windows_of(&json!({"model": {"id": "opus"}})), None);
        assert_eq!(windows_of(&json!({"rate_limits": "27%"})), None);
        assert_eq!(windows_of(&Value::Null), None);
    }

    #[test]
    fn nonsense_figures_do_not_make_a_window() {
        for bad in [json!(-1), json!(-0.5), json!(true), json!("7"), json!(null), json!([7])] {
            let p = payload(json!({"used_percentage": bad, "resets_at": 1787803200}), Value::Null);
            assert_eq!(windows_of(&p), None, "{bad:?}");
        }
        for bad in [json!(0), json!(-1), json!(null), json!("x")] {
            let p = payload(json!({"used_percentage": 27, "resets_at": bad}), Value::Null);
            assert_eq!(windows_of(&p), None, "{bad:?}");
        }
    }

    #[test]
    fn a_float_reset_time_is_accepted() {
        let p = payload(json!({"used_percentage": 27, "resets_at": 1787883600.0}), Value::Null);
        assert_eq!(windows_of(&p).unwrap().five_hour.resets_at, 1787883600);
    }

    // ── regresses ────────────────────────────────────────────────────────────

    const RECORD: fn() -> Value = || {
        json!({"capturedAt": 1787797108,
               "five_hour": {"used_percentage": 22, "resets_at": 1787883600},
               "seven_day": {"used_percentage": 35, "resets_at": 1788206400}})
    };

    #[test]
    fn a_reading_from_an_already_reset_window_is_behind() {
        let new = reading((6, 1787865600), Some((35, 1788206400)));
        assert!(regresses(&new, Some(&RECORD())));
    }

    #[test]
    fn a_step_back_inside_the_same_window_is_behind() {
        let new = reading((6, 1787883600), Some((35, 1788206400)));
        assert!(regresses(&new, Some(&RECORD())));
    }

    #[test]
    fn a_step_back_in_the_weekly_window_alone_discards_the_whole_reading() {
        let new = reading((23, 1787883600), Some((32, 1788206400)));
        assert!(regresses(&new, Some(&RECORD())));
    }

    #[test]
    fn growth_inside_the_window_is_not_a_regression() {
        let new = reading((23, 1787883600), Some((36, 1788206400)));
        assert!(!regresses(&new, Some(&RECORD())));
    }

    #[test]
    fn a_window_that_has_genuinely_reset_may_drop_to_zero() {
        let new = reading((0, 1787901600), Some((35, 1788206400)));
        assert!(!regresses(&new, Some(&RECORD())));
    }

    #[test]
    fn a_corrupt_record_does_not_protect_itself() {
        let corrupt = json!({"capturedAt": 1787797108,
                             "five_hour": {"used_percentage": "нет", "resets_at": null}});
        let new = reading((22, 1787883600), None);
        assert!(!regresses(&new, Some(&corrupt)));
    }

    #[test]
    fn nothing_recorded_is_never_a_regression() {
        assert!(!regresses(&reading((0, 1), None), None));
    }

    // ── unchanged ────────────────────────────────────────────────────────────

    #[test]
    fn identical_figures_are_unchanged() {
        let new = reading((22, 1787883600), Some((35, 1788206400)));
        assert!(unchanged(&new, Some(&RECORD())));
    }

    #[test]
    fn a_record_written_in_another_notation_is_still_unchanged() {
        // Design D6: Value::Number(27) != Value::Number(27.0), but 27 == 27.0.
        let recorded = json!({"capturedAt": 1787797108,
                              "five_hour": {"used_percentage": 22.0, "resets_at": 1787883600.0}});
        assert!(unchanged(&reading((22, 1787883600), None), Some(&recorded)));
    }

    #[test]
    fn a_reset_time_change_alone_counts_as_a_change() {
        let new = reading((22, 1787901600), Some((35, 1788206400)));
        assert!(!unchanged(&new, Some(&RECORD())));
    }

    #[test]
    fn a_window_disappearing_counts_as_a_change() {
        assert!(!unchanged(&reading((22, 1787883600), None), Some(&RECORD())));
    }

    #[test]
    fn an_extra_key_in_a_recorded_window_counts_as_a_change() {
        let recorded = json!({"capturedAt": 1,
                              "five_hour": {"used_percentage": 22, "resets_at": 1787883600, "x": 1}});
        assert!(!unchanged(&reading((22, 1787883600), None), Some(&recorded)));
    }

    #[test]
    fn a_record_without_an_integer_capture_time_is_rewritten() {
        for bad in [json!(null), json!("now"), json!(1787797108.0)] {
            let recorded = json!({"capturedAt": bad,
                                  "five_hour": {"used_percentage": 22, "resets_at": 1787883600}});
            assert!(!unchanged(&reading((22, 1787883600), None), Some(&recorded)), "{bad:?}");
        }
    }

    // ── decide ───────────────────────────────────────────────────────────────

    #[test]
    fn a_fresh_reading_is_written_with_the_current_time() {
        let p = payload(
            json!({"used_percentage": 27, "resets_at": 1787803200}),
            json!({"used_percentage": 24, "resets_at": 1788206400}),
        );
        assert_eq!(
            decide(&p, None, 1787797108),
            Decision::Write(
                "{\"capturedAt\": 1787797108, \
                  \"five_hour\": {\"used_percentage\": 27, \"resets_at\": 1787803200}, \
                  \"seven_day\": {\"used_percentage\": 24, \"resets_at\": 1788206400}}"
                    .into()
            )
        );
    }

    #[test]
    fn unusable_lagging_and_unchanged_readings_all_leave_the_record_alone() {
        let record = RECORD();
        let no_limits = json!({"model": {"id": "opus"}});
        assert_eq!(decide(&no_limits, Some(&record), 9), Decision::Leave);

        let lagging = payload(json!({"used_percentage": 6, "resets_at": 1787883600}), Value::Null);
        assert_eq!(decide(&lagging, Some(&record), 9), Decision::Leave);

        let same = payload(
            json!({"used_percentage": 22, "resets_at": 1787883600}),
            json!({"used_percentage": 35, "resets_at": 1788206400}),
        );
        assert_eq!(decide(&same, Some(&record), 9), Decision::Leave);
    }

    #[test]
    fn rounding_noise_alone_is_not_a_change() {
        let record = json!({"capturedAt": 1, "five_hour": {"used_percentage": 7, "resets_at": 2}});
        let p = payload(json!({"used_percentage": 7.0000002, "resets_at": 2}), Value::Null);
        assert_eq!(decide(&p, Some(&record), 9), Decision::Leave);
    }
}

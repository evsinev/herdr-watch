//! The rendered line.
//!
//! Ported element for element from
//! `openspec/changes/render-statusline-directly/reference/statusline.py`.
//!
//! Everything arriving in the payload is data, not instructions: nothing is
//! executed and the transcript is opened read-only. Any field may be absent, `null`
//! or of the wrong type — an element without data drops out together with its
//! separator rather than breaking the line.

use std::path::Path;

use serde_json::Value;

use crate::fmt::{
    BAR_CELLS, BOLD, CYAN, DIM, GREEN, Palette, RED, YELLOW, fmt_left, fmt_num, fmt_span,
};
use crate::json::{get, get2, is_exactly, num, text};
use crate::round::{display_percent, half_even};
use crate::transcript::tokens_from_transcript;

/// Context window sizes assumed when the payload does not report one.
const WINDOW_1M: f64 = 1_000_000.0;
const WINDOW_DEFAULT: f64 = 200_000.0;

/// Everything the line needs besides the payload itself.
#[derive(Debug, Clone, Copy)]
pub struct Context {
    pub palette: Palette,
    /// Wall clock in epoch seconds, injected so the line is a pure function.
    pub now: f64,
    /// Design D10: off unless the operator asked for it.
    pub transcript_fallback: bool,
}

/// The whole line, without its trailing newline.
pub fn render(payload: &Value, ctx: &Context) -> String {
    let segments = [
        segment_model(payload, ctx),
        segment_context(payload, ctx),
        segment_lines(payload, ctx),
        segment_cost(payload, ctx),
        segment_limits(payload, ctx),
    ];
    let separator = ctx.palette.paint(" | ", &[DIM]);
    segments.iter().filter(|s| !s.is_empty()).cloned().collect::<Vec<_>>().join(&separator)
}

fn segment_model(payload: &Value, ctx: &Context) -> String {
    let model = get(payload, "model");
    let Some(name) = text(get(model, "display_name")).or_else(|| text(get(model, "id"))) else {
        return String::new();
    };
    let mut out = ctx.palette.paint(name, &[CYAN]);
    if let Some(effort) = text(get2(payload, "effort", "level")) {
        out.push_str(" · ");
        out.push_str(effort);
    }
    if is_exactly(get(payload, "fast_mode"), true) {
        out.push_str(&ctx.palette.paint(" · fast", &[YELLOW]));
    }
    if is_exactly(get2(payload, "thinking", "enabled"), false) {
        out.push_str(&ctx.palette.paint(" · no-think", &[DIM]));
    }
    if let Some(style) = text(get2(payload, "output_style", "name"))
        && style != "default"
    {
        out.push_str(&ctx.palette.paint(&format!(" · {style}"), &[DIM]));
    }
    out
}

fn segment_context(payload: &Value, ctx: &Context) -> String {
    let window = get(payload, "context_window");

    let used = match num(get(window, "total_input_tokens")) {
        Some(used) => used,
        // Design D10: the transcript is read only when the operator asked for it.
        None if ctx.transcript_fallback => {
            match text(get(payload, "transcript_path"))
                .map(Path::new)
                .and_then(tokens_from_transcript)
            {
                Some(used) => used,
                None => return String::new(),
            }
        }
        None => return String::new(),
    };

    let size = match num(get(window, "context_window_size")).filter(|s| *s > 0.0) {
        Some(size) => size,
        None => {
            let id = text(get2(payload, "model", "id")).unwrap_or("");
            if id.contains("[1m]") { WINDOW_1M } else { WINDOW_DEFAULT }
        }
    };

    // Two rounding rules in one element, preserved from the original on purpose: a
    // percentage the payload supplies is truncated, one we compute is rounded.
    let percent = match num(get(window, "used_percentage")) {
        Some(reported) => (reported as i64).clamp(0, 100),
        None => display_percent(half_even(used / size * 100.0)),
    };

    let mut filled =
        (half_even(percent as f64 / 100.0 * BAR_CELLS as f64) as i64).clamp(0, BAR_CELLS);
    if percent > 0 && filled == 0 {
        filled = 1; // non-zero consumption has to be visible
    }

    let colour = ctx.palette.pressure(percent);
    let bar = format!(
        "{}{}",
        ctx.palette.paint(&"█".repeat(filled as usize), &[colour]),
        ctx.palette.paint(&"░".repeat((BAR_CELLS - filled) as usize), &[DIM])
    );
    format!(
        "Ctx [{}] {}/{} ({})",
        bar,
        fmt_num(used),
        fmt_num(size),
        ctx.palette.paint(&format!("{percent}%"), &[colour])
    )
}

fn segment_lines(payload: &Value, ctx: &Context) -> String {
    let cost = get(payload, "cost");
    // Python used `as_num(...) or 0`, where 0.0 is falsy — same result, kept as is.
    let added = num(get(cost, "total_lines_added")).unwrap_or(0.0);
    let removed = num(get(cost, "total_lines_removed")).unwrap_or(0.0);
    if added <= 0.0 && removed <= 0.0 {
        return String::new();
    }
    format!(
        "{}{}",
        ctx.palette.paint(&format!("+{}", added as i64), &[GREEN]),
        ctx.palette.paint(&format!("/-{}", removed as i64), &[RED])
    )
}

fn segment_cost(payload: &Value, ctx: &Context) -> String {
    let data = get(payload, "cost");
    let Some(cost) = num(get(data, "total_cost_usd")) else { return String::new() };
    let mut out = format!(
        "{} {}",
        ctx.palette.paint("Cost", &[DIM]),
        ctx.palette.paint(&format!("${cost:.2}"), &[BOLD])
    );

    // Duration from the first minute of the session: seconds are only noise here.
    let Some(wall_ms) = num(get(data, "total_duration_ms")).filter(|ms| *ms >= 60000.0) else {
        return out;
    };
    let mut tail = fmt_span(wall_ms / 1000.0);
    if let Some(api_ms) = num(get(data, "total_api_duration_ms")).filter(|ms| *ms > 0.0) {
        tail.push_str(&format!(" (api {})", fmt_span(api_ms / 1000.0)));
    }
    out.push_str(&ctx.palette.paint(&format!(" · {tail}"), &[DIM]));
    out
}

fn segment_limits(payload: &Value, ctx: &Context) -> String {
    // The key is absent until the session has observed its rate limits.
    let limits = get(payload, "rate_limits");
    let mut parts: Vec<String> = Vec::with_capacity(2);

    if let Some(percent) = num(get2(limits, "five_hour", "used_percentage")) {
        let percent = display_percent(percent);
        let mut part = format!(
            "5h {}",
            ctx.palette.paint(&format!("{percent}%"), &[ctx.palette.pressure(percent)])
        );
        if let Some(resets_at) = num(get2(limits, "five_hour", "resets_at")) {
            let left = resets_at - ctx.now;
            if left > 0.0 {
                part.push_str(&format!(" ({})", fmt_left(left)));
            }
        }
        parts.push(part);
    }

    if let Some(percent) = num(get2(limits, "seven_day", "used_percentage")) {
        let percent = display_percent(percent);
        parts.push(format!(
            "7d {}",
            ctx.palette.paint(&format!("{percent}%"), &[ctx.palette.pressure(percent)])
        ));
    }

    parts.join(" · ")
}

#[cfg(test)]
#[allow(clippy::unwrap_used, clippy::panic, clippy::indexing_slicing)]
mod tests {
    use super::*;
    use crate::fmt::Thresholds;
    use serde_json::json;

    fn ctx() -> Context {
        Context { palette: Palette::plain(), now: 1_787_797_108.0, transcript_fallback: false }
    }

    #[test]
    fn a_complete_payload_renders_every_element() {
        let payload = json!({
            "model": {"display_name": "Opus 5", "id": "claude-opus-5[1m]"},
            "effort": {"level": "high"},
            "context_window": {"total_input_tokens": 185_300, "context_window_size": 1_000_000,
                               "used_percentage": 19},
            "cost": {"total_cost_usd": 8.875, "total_duration_ms": 2_580_000,
                     "total_api_duration_ms": 1_140_000,
                     "total_lines_added": 1055, "total_lines_removed": 37},
            "rate_limits": {"five_hour": {"used_percentage": 43, "resets_at": 1_787_805_088},
                            "seven_day": {"used_percentage": 18, "resets_at": 1_788_206_400}}
        });
        assert_eq!(
            render(&payload, &ctx()),
            "Opus 5 · high | Ctx [██░░░░░░░░] 185.3k/1.0M (19%) | +1055/-37 \
             | Cost $8.88 · 43m (api 19m) | 5h 43% (2h13m) · 7d 18%"
        );
    }

    #[test]
    fn an_empty_payload_renders_an_empty_line() {
        assert_eq!(render(&json!({}), &ctx()), "");
        assert_eq!(render(&Value::Null, &ctx()), "");
    }

    #[test]
    fn a_wrongly_typed_field_drops_its_element_and_its_separator() {
        let payload = json!({
            "model": {"display_name": "Opus 5"},
            "context_window": {"total_input_tokens": "lots"},
            "cost": {"total_cost_usd": 1.0}
        });
        assert_eq!(render(&payload, &ctx()), "Opus 5 | Cost $1.00");
    }

    #[test]
    fn quota_not_yet_observed_shows_nothing_rather_than_zero() {
        let payload = json!({"model": {"id": "opus"}});
        assert_eq!(render(&payload, &ctx()), "opus");
    }

    #[test]
    fn model_flags_are_strict_about_their_types() {
        let base = json!({"model": {"display_name": "Opus 5"}});
        assert_eq!(render(&base, &ctx()), "Opus 5");

        let mut payload = base.clone();
        payload["fast_mode"] = json!(1); // not `true`
        assert_eq!(render(&payload, &ctx()), "Opus 5");

        payload["fast_mode"] = json!(true);
        payload["thinking"] = json!({"enabled": false});
        payload["output_style"] = json!({"name": "concise"});
        assert_eq!(render(&payload, &ctx()), "Opus 5 · fast · no-think · concise");

        payload["output_style"] = json!({"name": "default"});
        assert_eq!(render(&payload, &ctx()), "Opus 5 · fast · no-think");
    }

    #[test]
    fn the_context_window_size_is_guessed_from_the_model_id() {
        let big = json!({"model": {"id": "claude-opus-5[1m]"},
                         "context_window": {"total_input_tokens": 100_000}});
        assert!(render(&big, &ctx()).contains("100.0k/1.0M (10%)"));

        let small = json!({"model": {"id": "claude-opus-5"},
                           "context_window": {"total_input_tokens": 100_000}});
        assert!(render(&small, &ctx()).contains("100.0k/200.0k (50%)"));
    }

    #[test]
    fn non_zero_consumption_always_shows_at_least_one_cell() {
        // 1 % is a tenth of a cell, which rounds to none — but a consumption that
        // is not zero has to be visible, so one cell is forced.
        let payload = json!({"context_window": {"total_input_tokens": 2000,
                                                "context_window_size": 200_000}});
        assert_eq!(
            render(&payload, &ctx()),
            "Ctx [\u{2588}\u{2591}\u{2591}\u{2591}\u{2591}\u{2591}\u{2591}\u{2591}\u{2591}\u{2591}] 2.0k/200.0k (1%)"
        );

        // Half a percent genuinely rounds to zero, and then no cell is filled.
        let rounds_to_zero = json!({"context_window": {"total_input_tokens": 1000,
                                                       "context_window_size": 200_000}});
        assert_eq!(render(&rounds_to_zero, &ctx()).matches('\u{2588}').count(), 0);
    }

    #[test]
    fn the_bar_rounds_to_even_at_exactly_half_a_cell() {
        // Design D4b. 25 % is 2.5 cells: half-to-even gives 2, and byte parity with
        // the renderer being replaced depends on it. Do not "simplify" to round().
        for (percent, cells) in [(25, 2), (45, 4), (65, 6), (85, 8), (35, 4), (15, 2)] {
            let payload = json!({"context_window": {"total_input_tokens": 1,
                                                    "context_window_size": 100,
                                                    "used_percentage": percent}});
            let line = render(&payload, &ctx());
            let filled = line.matches('█').count();
            assert_eq!(filled, cells, "at {percent}%: {line}");
        }
    }

    #[test]
    fn lines_changed_drops_out_when_nothing_changed() {
        let none = json!({"cost": {"total_lines_added": 0, "total_lines_removed": 0}});
        assert_eq!(render(&none, &ctx()), "");
        let some = json!({"cost": {"total_lines_added": 0, "total_lines_removed": 4}});
        assert_eq!(render(&some, &ctx()), "+0/-4");
    }

    #[test]
    fn cost_shows_a_duration_only_from_the_first_minute() {
        let short = json!({"cost": {"total_cost_usd": 0.5, "total_duration_ms": 59_000}});
        assert_eq!(render(&short, &ctx()), "Cost $0.50");

        let long = json!({"cost": {"total_cost_usd": 0.5, "total_duration_ms": 60_000}});
        assert_eq!(render(&long, &ctx()), "Cost $0.50 · 1m");

        let with_api = json!({"cost": {"total_cost_usd": 0.5, "total_duration_ms": 60_000,
                                       "total_api_duration_ms": 18_000}});
        assert_eq!(render(&with_api, &ctx()), "Cost $0.50 · 1m (api 18s)");
    }

    #[test]
    fn a_reset_time_in_the_past_claims_no_remaining_time() {
        let payload = json!({"rate_limits": {"five_hour": {"used_percentage": 43,
                                                           "resets_at": 1_000_000}}});
        assert_eq!(render(&payload, &ctx()), "5h 43%");
    }

    #[test]
    fn the_context_element_is_absent_until_the_fallback_is_asked_for() {
        let payload = json!({"transcript_path": "/nonexistent.jsonl", "model": {"id": "opus"}});
        assert_eq!(render(&payload, &ctx()), "opus");

        let mut ctx = ctx();
        ctx.transcript_fallback = true;
        assert_eq!(render(&payload, &ctx), "opus"); // enabled, but the file is not there
    }

    #[test]
    fn the_operators_thresholds_move_where_the_colour_changes() {
        let payload = json!({"rate_limits": {"five_hour": {"used_percentage": 65}}});
        let mut ctx = ctx();

        ctx.palette = Palette::new(true, Thresholds::default());
        assert!(render(&payload, &ctx).contains(YELLOW), "60/85: 65 % is a warning");

        ctx.palette = Palette::new(true, Thresholds { warn_at: 70, critical_at: 90 });
        assert!(render(&payload, &ctx).contains(GREEN), "70/90: 65 % is still fine");
    }

    #[test]
    fn colour_is_present_when_asked_for_and_the_line_always_terminates_it() {
        let payload = json!({"model": {"display_name": "Opus 5"}});
        let ctx = Context {
            palette: Palette::new(true, Thresholds::default()),
            now: 0.0,
            transcript_fallback: false,
        };
        assert_eq!(render(&payload, &ctx), format!("{CYAN}Opus 5{}", crate::fmt::RESET));
    }
}

//! The record's contract, end to end.
//!
//! Ported case for case from `scripts/test_herdr_watch_statusline_hook.py`, which
//! was the executable specification of the Python hook this binary replaces. Two of
//! its cases died with the pass-through wrapper — "the exit status is the wrapped
//! command's" and "a missing command exits 127" — because there is no wrapped
//! command any more.

mod common;

use std::fs;
use std::os::unix::fs::PermissionsExt;
use std::thread;

use common::{capture, mtime, payload, read, run, seed, state_path};
use serde_json::json;

/// The record used as the "already recorded" starting point, matching the Python
/// suite's `GOOD`.
const RECORDED: &str = r#"{"capturedAt": 1787797108, "five_hour": {"used_percentage": 22, "resets_at": 1787883600}, "seven_day": {"used_percentage": 35, "resets_at": 1788206400}}"#;

// ── a reading arrives ────────────────────────────────────────────────────────

#[test]
fn both_windows_are_recorded_and_a_line_is_rendered() {
    let dir = tempfile::tempdir().unwrap();
    let path = state_path(&dir);

    let run = run(&path, &payload(Some((27.0, 1787803200)), Some((24.0, 1788206400))), &[]);
    assert_eq!(run.status, 0);
    assert!(run.stderr.is_empty());
    assert!(run.stdout.ends_with(b"\n"), "a line is always terminated");

    let record = read(&path).unwrap();
    assert_eq!(record["five_hour"], json!({"used_percentage": 27, "resets_at": 1787803200}));
    assert_eq!(record["seven_day"], json!({"used_percentage": 24, "resets_at": 1788206400}));
    assert!(record["capturedAt"].is_i64());
}

#[test]
fn one_window_only_the_other_is_absent_not_zero() {
    let dir = tempfile::tempdir().unwrap();
    let path = state_path(&dir);

    capture(&path, &payload(Some((27.0, 1787803200)), None));
    let record = read(&path).unwrap();
    assert!(record.get("seven_day").is_none(), "an absent window is absent, never a zero");
}

#[test]
fn an_incomplete_session_window_makes_the_whole_reading_unusable() {
    let dir = tempfile::tempdir().unwrap();
    let path = state_path(&dir);

    let stdin = serde_json::to_vec(&json!({
        "rate_limits": {"five_hour": {"used_percentage": 27},
                        "seven_day": {"used_percentage": 24, "resets_at": 1788206400}}
    }))
    .unwrap();
    let run = capture(&path, &stdin);

    assert_eq!(run.status, 0);
    assert!(!path.exists(), "no file at all — a valid weekly window cannot rescue the reading");
}

#[test]
fn a_payload_without_quota_leaves_the_previous_record_untouched() {
    let dir = tempfile::tempdir().unwrap();
    let path = state_path(&dir);
    seed(&path, RECORDED);
    let before = mtime(&path);

    capture(&path, br#"{"session_id": "s", "model": {"id": "opus"}}"#);

    assert_eq!(fs::read_to_string(&path).unwrap(), RECORDED);
    assert_eq!(mtime(&path), before);
}

#[test]
fn malformed_and_unexpected_input_records_nothing_and_still_succeeds() {
    let dir = tempfile::tempdir().unwrap();

    for stdin in [
        &b"not json at all {{{"[..],
        &br#"{"rate_limits": {"five_hour": "27%"}}"#[..],
        &br#"{"rate_limits": "everything is fine"}"#[..],
        &b""[..],
    ] {
        let path = state_path(&dir).with_extension(format!("{}.json", stdin.len()));
        let run = capture(&path, stdin);
        assert_eq!(run.status, 0, "{stdin:?}");
        assert!(run.stderr.is_empty(), "{stdin:?}");
        assert!(!path.exists(), "{stdin:?}");
    }
}

#[test]
fn invalid_utf8_and_a_closed_stdin_are_both_survivable() {
    let dir = tempfile::tempdir().unwrap();
    let path = state_path(&dir);

    let run = run(&path, &[0xff, 0xfe, 0x00, b'{'], &[]);
    assert_eq!(run.status, 0);
    assert!(run.stderr.is_empty());
    assert_eq!(run.stdout, b"\n", "an empty line, not a diagnostic");
    assert!(!path.exists());
}

// ── figures arriving as fractions ────────────────────────────────────────────

#[test]
fn fractional_figures_are_recorded_rounded_rather_than_dropped() {
    let dir = tempfile::tempdir().unwrap();

    // 7.000000000000001 is what a live payload actually carries.
    for (raw, expected) in
        [(7.000000000000001, 7), (7.4, 7), (7.6, 8), (0.2, 0), (99.5, 100), (104.7, 100)]
    {
        let path = state_path(&dir).with_extension(format!("{expected}.json"));
        capture(&path, &payload(Some((raw, 1787803200)), None));
        assert_eq!(read(&path).unwrap()["five_hour"]["used_percentage"], json!(expected), "{raw}");
    }
}

#[test]
fn nonsense_figures_produce_no_record_at_all() {
    let dir = tempfile::tempdir().unwrap();

    for (index, used) in [json!(-1), json!(-0.5), json!(true), json!("7"), json!(null), json!([7])]
        .into_iter()
        .enumerate()
    {
        let path = state_path(&dir).with_extension(format!("{index}.json"));
        let stdin = serde_json::to_vec(
            &json!({"rate_limits": {"five_hour": {"used_percentage": used, "resets_at": 1787803200}}}),
        )
        .unwrap();
        capture(&path, &stdin);
        assert!(!path.exists(), "case {index}");
    }
}

// ── the capture time must not overstate freshness ────────────────────────────

#[test]
fn an_identical_payload_rewrites_neither_the_bytes_nor_the_modification_time() {
    let dir = tempfile::tempdir().unwrap();
    let path = state_path(&dir);
    let stdin = payload(Some((27.0, 1787803200)), Some((24.0, 1788206400)));

    capture(&path, &stdin);
    let (before_bytes, before_mtime) = (fs::read(&path).unwrap(), mtime(&path));
    thread::sleep(std::time::Duration::from_millis(1100));
    capture(&path, &stdin);

    assert_eq!(fs::read(&path).unwrap(), before_bytes);
    assert_eq!(mtime(&path), before_mtime, "the reader polls by mtime — it must not move");
}

#[test]
fn rounding_noise_alone_is_not_a_rewrite() {
    let dir = tempfile::tempdir().unwrap();
    let path = state_path(&dir);

    capture(&path, &payload(Some((7.0000001, 1787803200)), None));
    let before = mtime(&path);
    thread::sleep(std::time::Duration::from_millis(1100));
    capture(&path, &payload(Some((7.0000002, 1787803200)), None));

    assert_eq!(mtime(&path), before);
}

#[test]
fn a_record_written_by_the_python_hook_is_recognised_as_unchanged() {
    // The cross-version migration guarantee (design D6): the previous writer used
    // `json.dump` defaults, and `Value::Number(27) != Value::Number(27.0)` in Rust.
    let dir = tempfile::tempdir().unwrap();
    let path = state_path(&dir);
    seed(&path, RECORDED);
    let before = mtime(&path);

    capture(&path, &payload(Some((22.0, 1787883600)), Some((35.0, 1788206400))));

    assert_eq!(fs::read_to_string(&path).unwrap(), RECORDED, "not one byte moved");
    assert_eq!(mtime(&path), before);
}

#[test]
fn a_record_in_another_notation_is_still_unchanged() {
    let dir = tempfile::tempdir().unwrap();
    let path = state_path(&dir);
    seed(
        &path,
        r#"{"capturedAt": 1787797108, "five_hour": {"used_percentage": 22.0, "resets_at": 1787883600.0}}"#,
    );
    let before = mtime(&path);

    capture(&path, &payload(Some((22.0, 1787883600)), None));

    assert_eq!(mtime(&path), before, "27 and 27.0 are the same figure");
}

#[test]
fn changed_figures_rewrite_and_advance_the_capture_time() {
    let dir = tempfile::tempdir().unwrap();
    let path = state_path(&dir);
    seed(&path, RECORDED);

    capture(&path, &payload(Some((23.0, 1787883600)), Some((36.0, 1788206400))));
    let record = read(&path).unwrap();

    assert_eq!(record["five_hour"]["used_percentage"], json!(23));
    assert!(record["capturedAt"].as_i64().unwrap() > 1787797108);
}

#[test]
fn a_reset_time_change_alone_counts_as_a_change() {
    let dir = tempfile::tempdir().unwrap();
    let path = state_path(&dir);
    seed(&path, RECORDED);

    capture(&path, &payload(Some((22.0, 1787901600)), Some((35.0, 1788206400))));

    assert_eq!(read(&path).unwrap()["five_hour"]["resets_at"], json!(1787901600));
}

#[test]
fn a_window_disappearing_counts_as_a_change() {
    let dir = tempfile::tempdir().unwrap();
    let path = state_path(&dir);
    seed(&path, RECORDED);

    capture(&path, &payload(Some((22.0, 1787883600)), None));

    assert!(read(&path).unwrap().get("seven_day").is_none());
}

#[test]
fn a_corrupt_or_timeless_record_is_repaired_on_the_next_run() {
    let dir = tempfile::tempdir().unwrap();

    for (index, seeded) in [
        "{ broken",
        "[1, 2]",
        r#"{"five_hour": {"used_percentage": 22, "resets_at": 1787883600}}"#,
        r#"{"capturedAt": 1787797108.0, "five_hour": {"used_percentage": 22, "resets_at": 1787883600}}"#,
    ]
    .into_iter()
    .enumerate()
    {
        let path = state_path(&dir).with_extension(format!("{index}.json"));
        seed(&path, seeded);
        capture(&path, &payload(Some((22.0, 1787883600)), None));
        assert!(read(&path).unwrap()["capturedAt"].is_i64(), "case {index}: {seeded}");
    }
}

#[test]
fn an_extra_key_in_a_recorded_window_counts_as_a_change() {
    let dir = tempfile::tempdir().unwrap();
    let path = state_path(&dir);
    seed(
        &path,
        r#"{"capturedAt": 1, "five_hour": {"used_percentage": 22, "resets_at": 1787883600, "note": "x"}}"#,
    );

    capture(&path, &payload(Some((22.0, 1787883600)), None));

    assert!(read(&path).unwrap()["five_hour"].get("note").is_none());
}

// ── a lagging session must not corrupt the shared record ─────────────────────

#[test]
fn an_old_client_shape_is_ignored_and_cannot_overwrite_a_good_record() {
    let dir = tempfile::tempdir().unwrap();
    let path = state_path(&dir);
    let old_shape = serde_json::to_vec(&json!({
        "session_id": "s", "version": "2.1.245",
        "rate_limits": {"seven_day": {"used_percentage": 7, "resets_at": 1788206400}}
    }))
    .unwrap();

    let fresh = state_path(&dir).with_extension("fresh.json");
    capture(&fresh, &old_shape);
    assert!(!fresh.exists(), "an old-shape reading creates nothing");

    seed(&path, RECORDED);
    let before = mtime(&path);
    capture(&path, &old_shape);
    assert_eq!(fs::read_to_string(&path).unwrap(), RECORDED);
    assert_eq!(mtime(&path), before);
}

#[test]
fn a_reading_from_an_already_reset_window_is_ignored() {
    let dir = tempfile::tempdir().unwrap();
    let path = state_path(&dir);
    seed(&path, RECORDED);
    let before = mtime(&path);

    // resets_at five hours behind the record: a window that has already reset.
    capture(&path, &payload(Some((6.0, 1787865600)), Some((35.0, 1788206400))));

    assert_eq!(fs::read_to_string(&path).unwrap(), RECORDED);
    assert_eq!(mtime(&path), before);
}

#[test]
fn a_step_back_inside_the_recorded_window_is_ignored() {
    let dir = tempfile::tempdir().unwrap();
    let path = state_path(&dir);
    seed(&path, RECORDED);

    capture(&path, &payload(Some((6.0, 1787883600)), Some((35.0, 1788206400))));

    assert_eq!(fs::read_to_string(&path).unwrap(), RECORDED);
}

#[test]
fn a_step_back_in_the_weekly_window_alone_discards_the_whole_reading() {
    let dir = tempfile::tempdir().unwrap();
    let path = state_path(&dir);
    seed(&path, RECORDED);

    // The session window grew, but the weekly one fell back — still a lagging session.
    capture(&path, &payload(Some((23.0, 1787883600)), Some((32.0, 1788206400))));

    assert_eq!(fs::read_to_string(&path).unwrap(), RECORDED);
}

#[test]
fn a_window_that_has_genuinely_reset_may_drop_to_zero() {
    let dir = tempfile::tempdir().unwrap();
    let path = state_path(&dir);
    seed(&path, RECORDED);

    // resets_at moved FORWARD: this is a real reset, not a lagging session.
    capture(&path, &payload(Some((0.0, 1787901600)), Some((35.0, 1788206400))));

    assert_eq!(
        read(&path).unwrap()["five_hour"],
        json!({"used_percentage": 0, "resets_at": 1787901600})
    );
}

#[test]
fn a_lagging_session_cannot_delay_a_later_good_reading() {
    let dir = tempfile::tempdir().unwrap();
    let path = state_path(&dir);
    seed(&path, RECORDED);

    capture(&path, &payload(Some((6.0, 1787865600)), None)); // ignored
    capture(&path, &payload(Some((24.0, 1787883600)), Some((36.0, 1788206400)))); // accepted

    assert_eq!(read(&path).unwrap()["five_hour"]["used_percentage"], json!(24));
}

#[test]
fn a_corrupt_recorded_window_does_not_block_a_fresh_reading() {
    let dir = tempfile::tempdir().unwrap();
    let path = state_path(&dir);
    seed(
        &path,
        r#"{"capturedAt": 1787797108, "five_hour": {"used_percentage": "нет", "resets_at": null}}"#,
    );

    capture(&path, &payload(Some((22.0, 1787883600)), None));

    assert_eq!(read(&path).unwrap()["five_hour"]["used_percentage"], json!(22));
}

// ── transparency ─────────────────────────────────────────────────────────────

#[test]
fn an_unwritable_target_still_renders_the_line_and_succeeds() {
    let dir = tempfile::tempdir().unwrap();
    // A regular file where the state directory has to be.
    let blocker = dir.path().join("state");
    seed(&blocker, "not a directory");
    let path = blocker.join("claude-usage.json");

    let run = run(&path, &payload(Some((27.0, 1787803200)), None), &["--no-color"]);

    assert_eq!(run.status, 0);
    assert!(run.stderr.is_empty());
    assert!(run.stdout_str().contains("5h 27%"), "{}", run.stdout_str());
}

#[test]
fn the_record_is_not_readable_by_other_users() {
    let dir = tempfile::tempdir().unwrap();
    let path = state_path(&dir);

    capture(&path, &payload(Some((27.0, 1787803200)), None));

    assert_eq!(fs::metadata(&path).unwrap().permissions().mode() & 0o777, 0o600);
}

#[test]
fn recording_and_rendering_can_each_be_asked_for_alone() {
    let dir = tempfile::tempdir().unwrap();
    let stdin = payload(Some((27.0, 1787803200)), None);

    let capture_only = state_path(&dir).with_extension("capture.json");
    let recorded = run(&capture_only, &stdin, &["--capture-only"]);
    assert!(recorded.stdout.is_empty(), "record-only mode prints nothing at all");
    assert_eq!(recorded.status, 0);
    assert!(capture_only.exists());

    let render_only = state_path(&dir).with_extension("render.json");
    let rendered = run(&render_only, &stdin, &["--no-capture", "--no-color"]);
    assert!(rendered.stdout_str().contains("5h 27%"));
    assert!(!render_only.exists(), "render-only mode creates no file");
}

#[test]
fn an_unrecognised_argument_does_not_blank_the_line() {
    let dir = tempfile::tempdir().unwrap();
    let path = state_path(&dir);

    let run = run(
        &path,
        &payload(Some((27.0, 1787803200)), None),
        &["--wrap", "python3 x.py", "--no-color"],
    );

    assert_eq!(run.status, 0);
    assert!(run.stderr.is_empty());
    assert!(run.stdout_str().contains("5h 27%"));
}

#[test]
fn the_version_is_reported_without_reading_stdin() {
    let dir = tempfile::tempdir().unwrap();
    let path = state_path(&dir);

    let run = run(&path, &payload(Some((27.0, 1787803200)), None), &["--version"]);

    assert_eq!(run.status, 0);
    assert!(run.stdout_str().starts_with("herdr-watch-statusline "));
    assert!(!path.exists(), "--version records nothing");
}

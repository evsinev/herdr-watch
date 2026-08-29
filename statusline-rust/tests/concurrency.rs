//! Several Claude Code sessions write this file at once, with no lock between them.
//!
//! The guarantee is not that a particular writer wins — it is that no reader ever
//! sees a partial write, and that the file is always a complete valid record.

mod common;

use std::io::Write;
use std::process::{Command, Stdio};

use common::{BIN, payload, read, state_path};

#[test]
fn eight_concurrent_writers_leave_one_valid_record() {
    let dir = tempfile::tempdir().unwrap();
    let path = state_path(&dir);

    // Eight distinct figures, all growing, all in the same window — so any of them
    // is an acceptable winner and none is rejected as a regression.
    let children: Vec<_> = (0..8)
        .map(|i| {
            let stdin = payload(Some((f64::from(i) * 10.0, 1787883600)), None);
            let mut child = Command::new(BIN)
                .arg("--capture-only")
                .env("HERDR_WATCH_USAGE_FILE", &path)
                .stdin(Stdio::piped())
                .stdout(Stdio::piped())
                .stderr(Stdio::piped())
                .spawn()
                .expect("the binary under test must be spawnable");
            if let Some(mut sink) = child.stdin.take() {
                let _ = sink.write_all(&stdin);
            }
            child
        })
        .collect();

    for child in children {
        let out = child.wait_with_output().expect("every writer must terminate");
        assert_eq!(out.status.code(), Some(0));
        assert!(out.stderr.is_empty());
    }

    let record = read(&path).expect("the record must parse after eight concurrent writes");
    let used = record["five_hour"]["used_percentage"].as_i64().expect("a whole percent");
    assert!(
        (0..=70).contains(&used) && used % 10 == 0,
        "one of the eight writes, not a mixture: {used}"
    );
    assert!(record["capturedAt"].is_i64());

    // Nothing half-written was left in the directory.
    let leftovers: Vec<_> = std::fs::read_dir(path.parent().unwrap())
        .unwrap()
        .filter_map(Result::ok)
        .filter(|e| e.file_name().to_string_lossy().ends_with(".tmp"))
        .collect();
    assert!(leftovers.is_empty(), "{leftovers:?}");
}

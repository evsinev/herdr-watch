//! Driving the real binary, because the Python suite's value came precisely from
//! testing the process boundary: the exit status, the emptiness of stderr, a real
//! `rename`, real concurrent processes.

#![allow(dead_code)]

use std::fs;
use std::io::Write;
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::time::SystemTime;

pub const BIN: &str = env!("CARGO_BIN_EXE_herdr-watch-statusline");

pub struct Run {
    pub stdout: Vec<u8>,
    pub stderr: Vec<u8>,
    pub status: i32,
}

impl Run {
    pub fn stdout_str(&self) -> String {
        String::from_utf8_lossy(&self.stdout).into_owned()
    }
}

/// Invoke the binary with `stdin`, pointing its state file at `path`.
pub fn run(path: &Path, stdin: &[u8], args: &[&str]) -> Run {
    let mut child = Command::new(BIN)
        .args(args)
        .env("HERDR_WATCH_USAGE_FILE", path)
        .env_remove("NO_COLOR")
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .expect("the binary under test must be spawnable");
    if let Some(mut sink) = child.stdin.take() {
        let _ = sink.write_all(stdin);
    }
    let out = child.wait_with_output().expect("the binary under test must terminate");
    Run { stdout: out.stdout, stderr: out.stderr, status: out.status.code().unwrap_or(-1) }
}

/// Record only — no line on stdout, so capture assertions stay clean.
pub fn capture(path: &Path, stdin: &[u8]) -> Run {
    run(path, stdin, &["--capture-only"])
}

/// A state file nested one directory deep, so directory creation is exercised.
pub fn state_path(dir: &tempfile::TempDir) -> PathBuf {
    dir.path().join("state/claude-usage.json")
}

pub fn read(path: &Path) -> Option<serde_json::Value> {
    serde_json::from_slice(&fs::read(path).ok()?).ok()
}

pub fn mtime(path: &Path) -> SystemTime {
    fs::metadata(path).and_then(|m| m.modified()).expect("the record must exist")
}

pub fn seed(path: &Path, contents: &str) {
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent).expect("the state directory must be creatable");
    }
    fs::write(path, contents).expect("the record must be seedable");
}

/// A payload carrying the given windows. `None` omits the window entirely.
pub fn payload(five: Option<(f64, i64)>, seven: Option<(f64, i64)>) -> Vec<u8> {
    let mut limits = serde_json::Map::new();
    if let Some((used, resets)) = five {
        limits.insert(
            "five_hour".into(),
            serde_json::json!({"used_percentage": used, "resets_at": resets}),
        );
    }
    if let Some((used, resets)) = seven {
        limits.insert(
            "seven_day".into(),
            serde_json::json!({"used_percentage": used, "resets_at": resets}),
        );
    }
    serde_json::to_vec(&serde_json::json!({"session_id": "s", "rate_limits": limits}))
        .expect("a literal payload must serialize")
}

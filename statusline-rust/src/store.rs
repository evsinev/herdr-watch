//! Where the record lives, and how it is replaced (design D7).
//!
//! A reader must never see a partial write, several writers must be safe without a
//! lock, and the file must not be readable by other users of the machine.

use std::env;
use std::fs::{self, File, OpenOptions};
use std::io::Write;
use std::os::unix::fs::OpenOptionsExt;
use std::path::{Path, PathBuf};
use std::process;
use std::time::{SystemTime, UNIX_EPOCH};

use serde_json::Value;

pub const ENV_STATE_FILE: &str = "HERDR_WATCH_USAGE_FILE";
/// Where the record lives when nothing overrides it. The leading `~` is not
/// decoration: without it the path is relative and `absolute()` would resolve it
/// against the **current working directory** — so the record would follow whatever
/// directory the editor happened to launch the status line from, and the backend,
/// watching the real one, would report `NOT_CONFIGURED` forever.
pub const DEFAULT_STATE_FILE: &str = "~/.config/herdr-watch/claude-usage.json";

/// The state file to use: an explicit `--state-file`, else `$HERDR_WATCH_USAGE_FILE`,
/// else `~/.config/herdr-watch/claude-usage.json`.
///
/// An **empty** environment variable means "unset": the Python hook used
/// `os.environ.get(...) or DEFAULT`, and an empty string is falsy there.
pub fn state_file(explicit: Option<&str>) -> PathBuf {
    let raw = explicit
        .map(str::to_owned)
        .or_else(|| env::var(ENV_STATE_FILE).ok().filter(|s| !s.is_empty()))
        .unwrap_or_else(|| DEFAULT_STATE_FILE.to_owned());
    absolute(&expand_home(&raw))
}

/// `~` and `~/…` from `$HOME`. `~user` is not supported and is left literal — the
/// Python form went through `pwd`, which has no use here.
fn expand_home(raw: &str) -> PathBuf {
    let home = env::var("HOME").ok();
    match (raw, home) {
        ("~", Some(home)) => PathBuf::from(home),
        (raw, Some(home)) if raw.starts_with("~/") => {
            PathBuf::from(home).join(raw.trim_start_matches("~/"))
        }
        (raw, _) if raw.starts_with("~/") || raw == "~" => PathBuf::from(raw),
        (raw, _) => PathBuf::from(raw),
    }
}

fn absolute(path: &Path) -> PathBuf {
    if path.is_absolute() {
        return path.to_path_buf();
    }
    match env::current_dir() {
        Ok(cwd) => cwd.join(path),
        Err(_) => path.to_path_buf(),
    }
}

/// The parsed record, or `None` — missing, unreadable, corrupt and non-object all
/// collapse to "there is nothing recorded", exactly as in the Python hook.
pub fn read_existing(path: &Path) -> Option<Value> {
    let bytes = fs::read(path).ok()?;
    let value: Value = serde_json::from_slice(&bytes).ok()?;
    if value.is_object() { Some(value) } else { None }
}

/// Replace the file with `contents`, atomically.
///
/// A temp file in the **same directory** (so `rename` stays within one filesystem),
/// created with `O_CREAT|O_EXCL` at mode 0600, flushed to disk, then renamed over
/// the target. `rename` is atomic on POSIX: a reader sees either the whole old file
/// or the whole new one. Last writer wins — deliberately, there is no lock.
///
/// The 0600 is not incidental: Python's `mkstemp` creates 0600 and `os.replace`
/// preserves it, while a plain create would give `0644 & ~umask`. Widening the
/// permissions of a file in the user's config directory is not a side effect a
/// rewrite gets to have.
pub fn write_atomically(path: &Path, contents: &str) -> std::io::Result<()> {
    let directory = path.parent().unwrap_or(Path::new("."));
    fs::create_dir_all(directory)?;

    let (mut file, tmp) = create_temp(directory)?;
    let result = (|| {
        file.write_all(contents.as_bytes())?;
        file.sync_all()?;
        drop(file);
        fs::rename(&tmp, path)
    })();
    if result.is_err() {
        let _ = fs::remove_file(&tmp);
    }
    result
}

fn create_temp(directory: &Path) -> std::io::Result<(File, PathBuf)> {
    let pid = process::id();
    let mut last = None;
    // O_EXCL makes the name race-free; a handful of attempts covers the case where
    // two processes of the same pid-and-nanosecond somehow collide.
    for attempt in 0..5u32 {
        let nanos = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map(|d| d.subsec_nanos())
            .unwrap_or(attempt);
        let tmp = directory.join(format!(".claude-usage.{pid}.{nanos}.{attempt}.tmp"));
        match OpenOptions::new().write(true).create_new(true).mode(0o600).open(&tmp) {
            Ok(file) => return Ok((file, tmp)),
            Err(e) => last = Some(e),
        }
    }
    Err(last.unwrap_or_else(|| std::io::Error::other("could not create a temp file")))
}

/// Wall clock in epoch seconds; 0 if the clock is before the epoch.
pub fn now_secs() -> i64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_secs() as i64).unwrap_or(0)
}

#[cfg(test)]
#[allow(clippy::unwrap_used, clippy::panic, clippy::indexing_slicing)]
mod tests {
    use super::*;
    use std::os::unix::fs::PermissionsExt;

    #[test]
    fn an_explicit_path_wins_over_the_environment() {
        let path = state_file(Some("/tmp/explicit.json"));
        assert_eq!(path, PathBuf::from("/tmp/explicit.json"));
    }

    #[test]
    fn a_relative_path_is_made_absolute() {
        assert!(state_file(Some("relative.json")).is_absolute());
    }

    #[test]
    fn the_default_record_lives_under_home_not_under_the_working_directory() {
        // Regression: the default was once a relative path, so the record landed in
        // whatever directory the editor launched the status line from while the
        // backend watched the real one and reported NOT_CONFIGURED forever.
        let home = env::var("HOME").unwrap_or_else(|_| "/tmp".into());
        let path = state_file(Some(DEFAULT_STATE_FILE));

        assert_eq!(path, PathBuf::from(&home).join(".config/herdr-watch/claude-usage.json"));
        assert!(path.is_absolute());
        assert!(
            !path.starts_with(env::current_dir().unwrap_or_default()) || home.starts_with('/'),
            "the record must not be resolved against the working directory"
        );
    }

    #[test]
    fn a_tilde_expands_from_home() {
        // SAFETY: the test process owns its environment; no threads read HOME here.
        let home = env::var("HOME").unwrap_or_else(|_| "/tmp".into());
        let path = state_file(Some("~/x/y.json"));
        assert_eq!(path, PathBuf::from(&home).join("x/y.json"));
    }

    #[test]
    fn a_written_record_round_trips_and_is_private() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("nested/claude-usage.json");
        write_atomically(&path, "{\"capturedAt\": 7}").unwrap();

        assert_eq!(read_existing(&path).unwrap()["capturedAt"], serde_json::json!(7));
        let mode = fs::metadata(&path).unwrap().permissions().mode() & 0o777;
        assert_eq!(mode, 0o600, "state file must not be world-readable");
    }

    #[test]
    fn no_temp_files_are_left_behind() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("claude-usage.json");
        write_atomically(&path, "{}").unwrap();
        write_atomically(&path, "{\"capturedAt\": 1}").unwrap();

        let leftovers: Vec<_> = fs::read_dir(dir.path())
            .unwrap()
            .filter_map(Result::ok)
            .filter(|e| e.file_name().to_string_lossy().ends_with(".tmp"))
            .collect();
        assert!(leftovers.is_empty(), "{leftovers:?}");
    }

    #[test]
    fn missing_corrupt_and_non_object_records_all_read_as_nothing() {
        let dir = tempfile::tempdir().unwrap();
        assert_eq!(read_existing(&dir.path().join("absent.json")), None);

        let broken = dir.path().join("broken.json");
        fs::write(&broken, "{ broken").unwrap();
        assert_eq!(read_existing(&broken), None);

        let array = dir.path().join("array.json");
        fs::write(&array, "[1, 2]").unwrap();
        assert_eq!(read_existing(&array), None);
    }

    #[test]
    fn an_unwritable_target_is_an_error_not_a_panic() {
        let dir = tempfile::tempdir().unwrap();
        // A regular file where a directory has to be.
        let blocker = dir.path().join("blocked");
        fs::write(&blocker, "not a directory").unwrap();
        assert!(write_atomically(&blocker.join("claude-usage.json"), "{}").is_err());
    }
}

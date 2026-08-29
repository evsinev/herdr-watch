//! Estimating context consumption from the session transcript (design D10).
//!
//! This is the only thing the command reads besides its input, so it is **opt-in**
//! (`--transcript-fallback`). Without it the context element simply drops out when
//! the payload does not report consumption, like any other element with no data.
//!
//! The I/O is one bounded tail read; the scanning is a pure function over `&str`,
//! so the rules are testable without a file.

use std::fs::File;
use std::io::{Read, Seek, SeekFrom};
use std::path::Path;

use serde_json::Value;

use crate::json::{get, get2, num};

/// How much of the transcript to look at. Transcripts get large and only the last
/// record with usage matters.
pub const TAIL_BYTES: u64 = 256 * 1024;

/// Total input tokens of the most recent request in the transcript, if any.
pub fn tokens_from_transcript(path: &Path) -> Option<f64> {
    scan_tail(&read_tail(path)?)
}

fn read_tail(path: &Path) -> Option<String> {
    let mut file = File::open(path).ok()?;
    let size = file.seek(SeekFrom::End(0)).ok()?;
    let tail = size.min(TAIL_BYTES);
    file.seek(SeekFrom::Start(size - tail)).ok()?;
    let mut buffer = Vec::with_capacity(tail as usize);
    file.take(tail).read_to_end(&mut buffer).ok()?;
    Some(String::from_utf8_lossy(&buffer).into_owned())
}

/// Scan JSONL backwards for the last record carrying `message.usage`, and sum the
/// three input-token counts it may hold.
///
/// The first line of a tail is usually truncated, so anything that does not start
/// with `{` is skipped rather than treated as an error.
pub fn scan_tail(tail: &str) -> Option<f64> {
    for line in tail.lines().rev() {
        let line = line.trim();
        if !line.starts_with('{') {
            continue;
        }
        let Ok(record) = serde_json::from_str::<Value>(line) else { continue };
        let usage = get2(&record, "message", "usage");
        if !usage.is_object() {
            continue;
        }
        let mut total = 0.0;
        let mut found = false;
        for key in ["input_tokens", "cache_creation_input_tokens", "cache_read_input_tokens"] {
            if let Some(value) = num(get(usage, key)) {
                total += value;
                found = true;
            }
        }
        if found {
            return Some(total);
        }
    }
    None
}

#[cfg(test)]
#[allow(clippy::unwrap_used, clippy::panic)]
mod tests {
    use super::*;
    use std::io::Write;

    #[test]
    fn the_last_record_with_usage_wins_and_its_counts_are_summed() {
        let tail = concat!(
            r#"{"message":{"usage":{"input_tokens":1}}}"#,
            "\n",
            r#"{"message":{"usage":{"input_tokens":10,"cache_creation_input_tokens":5,"cache_read_input_tokens":100}}}"#,
            "\n",
            r#"{"type":"summary"}"#,
            "\n"
        );
        assert_eq!(scan_tail(tail), Some(115.0));
    }

    #[test]
    fn a_truncated_first_line_and_junk_are_skipped() {
        let tail = concat!(
            "ken\":9}}}\n",
            "not json at all\n",
            r#"{"message":{"usage":{"input_tokens":3}}}"#,
            "\n"
        );
        assert_eq!(scan_tail(tail), Some(3.0));
    }

    #[test]
    fn a_transcript_without_usage_yields_nothing() {
        assert_eq!(scan_tail(""), None);
        assert_eq!(scan_tail("{\"message\":{}}\n"), None);
        assert_eq!(scan_tail("{\"message\":{\"usage\":{}}}\n"), None);
    }

    #[test]
    fn wrongly_typed_counts_are_ignored_rather_than_summed() {
        let tail = r#"{"message":{"usage":{"input_tokens":"7","cache_read_input_tokens":2}}}"#;
        assert_eq!(scan_tail(tail), Some(2.0));
    }

    #[test]
    fn a_real_file_is_read_from_its_tail_only() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("transcript.jsonl");
        let mut file = File::create(&path).unwrap();
        // Well past TAIL_BYTES, so the early record must be invisible.
        for _ in 0..4000 {
            writeln!(
                file,
                r#"{{"message":{{"usage":{{"input_tokens":1}}}},"pad":"{}"}}"#,
                "x".repeat(100)
            )
            .unwrap();
        }
        writeln!(file, r#"{{"message":{{"usage":{{"input_tokens":42}}}}}}"#).unwrap();
        drop(file);

        assert_eq!(tokens_from_transcript(&path), Some(42.0));
    }

    #[test]
    fn a_missing_file_is_not_an_error() {
        assert_eq!(tokens_from_transcript(Path::new("/nonexistent/transcript.jsonl")), None);
    }
}

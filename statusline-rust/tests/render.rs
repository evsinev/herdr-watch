//! The rendered line, against a frozen corpus.
//!
//! Each `data/<name>.json` is a payload and each `data/<name>.expected` is the exact
//! bytes the line must be, ANSI codes included. The corpus was frozen during the
//! cutover by diffing this binary against the Python renderer it replaces
//! (`openspec/changes/render-statusline-directly/reference/statusline.py`); see that
//! change's `tasks.md §7`.
//!
//! `now` is pinned by the payload's own reset times being far in the future, and the
//! corpus deliberately avoids asserting on the remaining-time text, which moves.

mod common;

use std::fs;
use std::path::Path;

use common::{BIN, run, state_path};

#[test]
fn every_payload_in_the_corpus_renders_its_frozen_line() {
    let corpus = Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/data");
    let dir = tempfile::tempdir().unwrap();

    let mut checked = 0;
    let mut entries: Vec<_> = fs::read_dir(&corpus)
        .expect("the corpus directory must exist")
        .filter_map(Result::ok)
        .map(|e| e.path())
        .filter(|p| p.extension().is_some_and(|e| e == "json"))
        .collect();
    entries.sort();

    for payload in entries {
        let expected_path = payload.with_extension("expected");
        let expected = fs::read(&expected_path)
            .unwrap_or_else(|_| panic!("missing golden output for {}", payload.display()));
        let stdin = fs::read(&payload).expect("a corpus payload must be readable");

        let state = state_path(&dir).with_extension(format!("{}.json", checked));
        let run = run(&state, &stdin, &["--no-capture", "--transcript-fallback"]);

        assert_eq!(
            String::from_utf8_lossy(&run.stdout),
            String::from_utf8_lossy(&expected),
            "line differs for {}",
            payload.display()
        );
        assert!(run.stderr.is_empty(), "{}", payload.display());
        assert_eq!(run.status, 0, "{}", payload.display());
        checked += 1;
    }

    assert!(checked > 0, "the corpus must not be empty — see tasks.md §7.4");
}

#[test]
fn stderr_stays_empty_and_stdout_stays_one_line_whatever_arrives() {
    let dir = tempfile::tempdir().unwrap();

    let inputs: Vec<Vec<u8>> = vec![
        b"".to_vec(),
        b"not json at all {{{".to_vec(),
        b"[]".to_vec(),
        b"null".to_vec(),
        b"7".to_vec(),
        br#"{"model": 7, "cost": "free", "rate_limits": []}"#.to_vec(),
        br#"{"context_window": {"total_input_tokens": -1, "context_window_size": 0}}"#.to_vec(),
        vec![0xff, 0xfe, 0x00],
    ];

    for (index, stdin) in inputs.iter().enumerate() {
        let state = state_path(&dir).with_extension(format!("{index}.json"));
        let run = run(&state, stdin, &["--no-capture"]);

        assert!(run.stderr.is_empty(), "case {index}: {:?}", String::from_utf8_lossy(&run.stderr));
        assert_eq!(run.status, 0, "case {index}");
        assert_eq!(run.stdout.iter().filter(|b| **b == b'\n').count(), 1, "case {index}");
        assert!(run.stdout.ends_with(b"\n"), "case {index}");
    }

    // And the binary is genuinely being exercised, not skipped.
    assert!(Path::new(BIN).exists());
}

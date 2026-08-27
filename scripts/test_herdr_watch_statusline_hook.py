#!/usr/bin/env python3
"""Tests for the pass-through statusline hook.

Run: python3 scripts/test_herdr_watch_statusline_hook.py

The invariant under test is design D2: whatever happens to the capture, stdin
reaches the wrapped command byte-for-byte, the exit status is the wrapped
command's, and the hook contributes nothing to stdout.
"""

import json
import os
import subprocess
import sys
import tempfile
import unittest

HOOK = os.path.join(os.path.dirname(os.path.abspath(__file__)), "herdr-watch-statusline-hook.py")

# Wrapped command stand-in: echoes stdin back, exits with the code we give it.
ECHO = "import sys; sys.stdout.write(sys.stdin.read()); sys.exit(int(sys.argv[1]))"

BOTH = {
    "session_id": "abc",
    "rate_limits": {
        "five_hour": {"used_percentage": 27, "resets_at": 1787803200},
        "seven_day": {"used_percentage": 24, "resets_at": 1788206400},
    },
}


def run(payload, state_file, exit_code=0, wrapped=True):
    """Run the hook; returns (stdout, stderr, returncode)."""
    env = dict(os.environ, HERDR_WATCH_USAGE_FILE=state_file)
    cmd = [sys.executable, HOOK]
    if wrapped:
        cmd += [sys.executable, "-c", ECHO, str(exit_code)]
    p = subprocess.run(cmd, input=payload, capture_output=True, env=env)
    return p.stdout, p.stderr, p.returncode


class HookTest(unittest.TestCase):

    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.state = os.path.join(self.dir.name, "usage", "claude-usage.json")

    def tearDown(self):
        self.dir.cleanup()

    def read_state(self):
        with open(self.state, encoding="utf-8") as f:
            return json.load(f)

    # --- capture ---

    def test_both_windows_recorded_and_stdin_forwarded(self):
        payload = json.dumps(BOTH).encode()
        out, _, rc = run(payload, self.state)
        self.assertEqual(out, payload)      # forwarded verbatim, nothing of ours added
        self.assertEqual(rc, 0)
        rec = self.read_state()
        self.assertEqual(rec["five_hour"], {"used_percentage": 27, "resets_at": 1787803200})
        self.assertEqual(rec["seven_day"], {"used_percentage": 24, "resets_at": 1788206400})
        self.assertIsInstance(rec["capturedAt"], int)

    def test_one_window_only_the_other_is_absent_not_zero(self):
        payload = json.dumps({
            "rate_limits": {"five_hour": {"used_percentage": 27, "resets_at": 1787803200}}
        }).encode()
        out, _, rc = run(payload, self.state)
        self.assertEqual(out, payload)
        self.assertEqual(rc, 0)
        rec = self.read_state()
        self.assertIn("five_hour", rec)
        self.assertNotIn("seven_day", rec)   # absent, never a fabricated zero

    def test_incomplete_window_is_omitted(self):
        payload = json.dumps({
            "rate_limits": {
                "five_hour": {"used_percentage": 27},                    # no resets_at
                "seven_day": {"used_percentage": 24, "resets_at": 1788206400},
            }
        }).encode()
        run(payload, self.state)
        rec = self.read_state()
        self.assertNotIn("five_hour", rec)   # no placeholder reset time
        self.assertIn("seven_day", rec)

    def test_no_rate_limits_leaves_previous_record_untouched(self):
        run(json.dumps(BOTH).encode(), self.state)
        before = self.read_state()
        payload = json.dumps({"session_id": "abc", "model": {"id": "opus"}}).encode()
        out, _, rc = run(payload, self.state)
        self.assertEqual(out, payload)
        self.assertEqual(rc, 0)
        self.assertEqual(self.read_state(), before)

    def test_malformed_input_forwarded_and_not_recorded(self):
        payload = b"not json at all {{{"
        out, _, rc = run(payload, self.state)
        self.assertEqual(out, payload)
        self.assertEqual(rc, 0)
        self.assertFalse(os.path.exists(self.state))

    def test_unexpected_shape_forwarded_and_not_recorded(self):
        payload = json.dumps({"rate_limits": {"five_hour": "27%"}}).encode()
        out, _, rc = run(payload, self.state)
        self.assertEqual(out, payload)
        self.assertEqual(rc, 0)
        self.assertFalse(os.path.exists(self.state))

    def test_empty_stdin_forwarded(self):
        out, _, rc = run(b"", self.state)
        self.assertEqual(out, b"")
        self.assertEqual(rc, 0)

    # --- transparency ---

    def test_unwritable_target_still_forwards_and_succeeds(self):
        blocker = os.path.join(self.dir.name, "blocked")
        open(blocker, "w").close()                       # a file where a directory must be
        state = os.path.join(blocker, "claude-usage.json")
        payload = json.dumps(BOTH).encode()
        out, _, rc = run(payload, state)
        self.assertEqual(out, payload)
        self.assertEqual(rc, 0)

    def test_exit_status_is_the_wrapped_commands(self):
        for code in (0, 3, 42):
            _, _, rc = run(json.dumps(BOTH).encode(), self.state, exit_code=code)
            self.assertEqual(rc, code)

    def test_hook_writes_nothing_of_its_own_to_stdout(self):
        # Wrapped command prints a fixed marker and consumes stdin; stdout must be
        # exactly that marker — no diagnostics, no echoed payload, nothing.
        env = dict(os.environ, HERDR_WATCH_USAGE_FILE=self.state)
        p = subprocess.run(
            [sys.executable, HOOK, sys.executable, "-c",
             "import sys; sys.stdin.read(); sys.stdout.write('5h 27%')"],
            input=json.dumps(BOTH).encode(), capture_output=True, env=env)
        self.assertEqual(p.stdout, b"5h 27%")
        self.assertEqual(p.returncode, 0)

    def test_capture_only_mode_without_a_wrapped_command(self):
        out, _, rc = run(json.dumps(BOTH).encode(), self.state, wrapped=False)
        self.assertEqual(out, b"")
        self.assertEqual(rc, 0)
        self.assertIn("five_hour", self.read_state())

    def test_missing_wrapped_command_does_not_write_stdout(self):
        env = dict(os.environ, HERDR_WATCH_USAGE_FILE=self.state)
        p = subprocess.run([sys.executable, HOOK, "/nonexistent/statusline"],
                           input=json.dumps(BOTH).encode(), capture_output=True, env=env)
        self.assertEqual(p.stdout, b"")
        self.assertEqual(p.returncode, 127)

    def test_concurrent_writers_leave_a_valid_file(self):
        payloads = []
        for i in range(8):
            payloads.append(json.dumps({
                "rate_limits": {"five_hour": {"used_percentage": i * 10, "resets_at": 1787803200 + i}}
            }).encode())
        env = dict(os.environ, HERDR_WATCH_USAGE_FILE=self.state)
        procs = [subprocess.Popen([sys.executable, HOOK], stdin=subprocess.PIPE,
                                  stdout=subprocess.DEVNULL, env=env) for _ in payloads]
        for p, data in zip(procs, payloads):
            p.communicate(data)
        for p in procs:
            self.assertEqual(p.returncode, 0)
        rec = self.read_state()                          # parses = complete, not partial
        self.assertIn(rec["five_hour"]["used_percentage"], [i * 10 for i in range(8)])


if __name__ == "__main__":
    unittest.main(verbosity=2)

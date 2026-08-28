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

    def test_incomplete_five_hour_makes_the_whole_reading_unusable(self):
        # Без resets_at время сброса выдумывать нельзя, а без пригодного five_hour
        # показание неполное — такую запись мы не принимаем вовсе (см. windows_of).
        payload = json.dumps({
            "rate_limits": {
                "five_hour": {"used_percentage": 27},                    # no resets_at
                "seven_day": {"used_percentage": 24, "resets_at": 1788206400},
            }
        }).encode()
        out, _, rc = run(payload, self.state)
        self.assertEqual(out, payload)
        self.assertEqual(rc, 0)
        self.assertFalse(os.path.exists(self.state))

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

    # --- дробные проценты: из-за них пропадал 5-часовой бар ---

    def test_live_fractional_value_is_recorded_rounded(self):
        # Ровно то значение, что пришло от Claude Code, когда бар исчезал.
        payload = json.dumps({
            "rate_limits": {
                "five_hour": {"used_percentage": 7.000000000000001, "resets_at": 1787883600},
                "seven_day": {"used_percentage": 34, "resets_at": 1788206400},
            }
        }).encode()
        out, _, rc = run(payload, self.state)

        self.assertEqual(out, payload)
        self.assertEqual(rc, 0)
        rec = self.read_state()
        self.assertEqual(rec["five_hour"], {"used_percentage": 7, "resets_at": 1787883600},
                         "дробное значение не должно ронять окно")
        self.assertEqual(rec["seven_day"]["used_percentage"], 34)

    def test_fractional_values_are_rounded_to_the_nearest_percent(self):
        for raw, expected in ((7.4, 7), (7.6, 8), (0.2, 0), (99.5, 100)):
            with self.subTest(raw=raw):
                payload = json.dumps({
                    "rate_limits": {"five_hour": {"used_percentage": raw, "resets_at": 1787883600}}
                }).encode()
                run(payload, self.state)
                self.assertEqual(self.read_state()["five_hour"]["used_percentage"], expected)

    def test_overage_above_100_is_clamped_not_dropped(self):
        # Перерасход не должен гасить индикатор ровно тогда, когда он важнее всего.
        payload = json.dumps({
            "rate_limits": {"five_hour": {"used_percentage": 104.7, "resets_at": 1787883600}}
        }).encode()
        run(payload, self.state)
        self.assertEqual(self.read_state()["five_hour"]["used_percentage"], 100)

    def test_float_resets_at_is_accepted(self):
        payload = json.dumps({
            "rate_limits": {"five_hour": {"used_percentage": 7, "resets_at": 1787883600.0}}
        }).encode()
        run(payload, self.state)
        self.assertEqual(self.read_state()["five_hour"]["resets_at"], 1787883600)

    def test_still_rejects_nonsense_numbers(self):
        for raw in (-1, -0.5, True, "7", None, [7]):
            with self.subTest(raw=raw):
                payload = json.dumps({
                    "rate_limits": {"five_hour": {"used_percentage": raw, "resets_at": 1787883600}}
                }).encode()
                out, _, rc = run(payload, self.state)
                self.assertEqual(out, payload)
                self.assertEqual(rc, 0)
                self.assertFalse(os.path.exists(self.state), f"{raw!r} не должно записываться")

    def test_rounding_change_alone_is_not_a_rewrite(self):
        # 7.0000001 и 7.0000002 округляются в одно и то же — файл трогать незачем.
        first = json.dumps({
            "rate_limits": {"five_hour": {"used_percentage": 7.0000001, "resets_at": 1787883600}}
        }).encode()
        run(first, self.state)
        before = self.mtime_ns()
        second = json.dumps({
            "rate_limits": {"five_hour": {"used_percentage": 7.0000002, "resets_at": 1787883600}}
        }).encode()
        run(second, self.state)
        self.assertEqual(self.mtime_ns(), before)

    # --- capturedAt честен: пишем только когда цифры сдвинулись ---

    def seed(self, rec):
        """Положить готовую запись в state-файл (мимо хука)."""
        os.makedirs(os.path.dirname(self.state), exist_ok=True)
        with open(self.state, "w", encoding="utf-8") as f:
            json.dump(rec, f)

    def mtime_ns(self):
        return os.stat(self.state).st_mtime_ns

    def test_identical_payload_twice_does_not_rewrite_the_file(self):
        payload = json.dumps(BOTH).encode()
        run(payload, self.state)
        before_mtime, before_bytes = self.mtime_ns(), open(self.state, "rb").read()

        # Так выглядит тик statusLine.refreshInterval: тот же payload, API-вызова не было.
        out, _, rc = run(payload, self.state)

        self.assertEqual(out, payload)          # прозрачность не пострадала
        self.assertEqual(rc, 0)
        self.assertEqual(self.mtime_ns(), before_mtime, "файл не должен переписываться")
        self.assertEqual(open(self.state, "rb").read(), before_bytes)

    def test_changed_figures_rewrite_and_advance_captured_at(self):
        old_captured = 1000000000
        self.seed({
            "capturedAt": old_captured,
            "five_hour": {"used_percentage": 27, "resets_at": 1787803200},
            "seven_day": {"used_percentage": 24, "resets_at": 1788206400},
        })
        payload = json.dumps({
            "rate_limits": {
                "five_hour": {"used_percentage": 31, "resets_at": 1787803200},
                "seven_day": {"used_percentage": 24, "resets_at": 1788206400},
            }
        }).encode()
        run(payload, self.state)

        rec = self.read_state()
        self.assertEqual(rec["five_hour"]["used_percentage"], 31)
        self.assertGreater(rec["capturedAt"], old_captured, "время должно сдвинуться")

    def test_reset_time_change_alone_counts_as_a_change(self):
        # Окно сбросилось: процент тот же, resets_at новый — это новые показания.
        self.seed({
            "capturedAt": 1000000000,
            "five_hour": {"used_percentage": 27, "resets_at": 1787803200},
        })
        payload = json.dumps({
            "rate_limits": {"five_hour": {"used_percentage": 27, "resets_at": 1787821200}}
        }).encode()
        run(payload, self.state)

        self.assertEqual(self.read_state()["five_hour"]["resets_at"], 1787821200)

    def test_window_disappearing_counts_as_a_change(self):
        run(json.dumps(BOTH).encode(), self.state)
        payload = json.dumps({
            "rate_limits": {"five_hour": {"used_percentage": 27, "resets_at": 1787803200}}
        }).encode()
        run(payload, self.state)

        rec = self.read_state()
        self.assertIn("five_hour", rec)
        self.assertNotIn("seven_day", rec, "исчезнувшее окно — тоже изменение")

    def test_corrupt_existing_file_is_repaired_on_the_next_run(self):
        self.seed({"capturedAt": 1000000000, "five_hour": {"used_percentage": 27,
                                                           "resets_at": 1787803200}})
        with open(self.state, "w", encoding="utf-8") as f:
            f.write("{ broken")
        run(json.dumps(BOTH).encode(), self.state)

        rec = self.read_state()                  # снова разбирается = починен
        self.assertEqual(rec["five_hour"]["used_percentage"], 27)
        self.assertEqual(rec["seven_day"]["used_percentage"], 24)
        self.assertIsInstance(rec["capturedAt"], int)

    def test_record_without_captured_at_is_rewritten(self):
        self.seed({"five_hour": {"used_percentage": 27, "resets_at": 1787803200},
                   "seven_day": {"used_percentage": 24, "resets_at": 1788206400}})
        run(json.dumps(BOTH).encode(), self.state)

        self.assertIsInstance(self.read_state()["capturedAt"], int)

    # --- общий файл делят сессии Claude Code РАЗНЫХ версий ---

    OLD_SHAPE = {
        "session_id": "1b36bde1", "version": "2.1.245",
        "rate_limits": {"seven_day": {"used_percentage": 20, "resets_at": 1788206400}},
    }

    def test_old_client_shape_is_ignored_entirely(self):
        # Наблюдалось вживую: сессии 2.1.243/245/247 шлют rate_limits без five_hour,
        # и их seven_day (7.0 / 20 / 26 / 29) не сходится ни с аккаунтным 34, ни между собой.
        out, _, rc = run(json.dumps(self.OLD_SHAPE).encode(), self.state)

        self.assertEqual(out, json.dumps(self.OLD_SHAPE).encode())
        self.assertEqual(rc, 0)
        self.assertFalse(os.path.exists(self.state), "неполное показание не должно создавать запись")

    def test_old_client_cannot_overwrite_a_good_record(self):
        # Ровно тот сценарий, из-за которого 5-часовой бар пропадал каждые 5 минут.
        run(json.dumps(BOTH).encode(), self.state)
        good = self.read_state()
        before_mtime = self.mtime_ns()

        run(json.dumps(self.OLD_SHAPE).encode(), self.state)

        self.assertEqual(self.read_state(), good, "старая сессия не должна затирать хорошую запись")
        self.assertEqual(self.mtime_ns(), before_mtime, "файл вообще не должен трогаться")

    def test_a_newer_full_reading_still_wins(self):
        run(json.dumps(BOTH).encode(), self.state)
        run(json.dumps(self.OLD_SHAPE).encode(), self.state)      # шум от старой сессии
        fresh = json.dumps({
            "rate_limits": {
                "five_hour": {"used_percentage": 12, "resets_at": 1787883600},
                "seven_day": {"used_percentage": 34, "resets_at": 1788206400},
            }
        }).encode()
        run(fresh, self.state)

        rec = self.read_state()
        self.assertEqual(rec["five_hour"]["used_percentage"], 12)
        self.assertEqual(rec["seven_day"]["used_percentage"], 34)

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

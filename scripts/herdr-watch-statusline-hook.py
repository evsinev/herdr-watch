#!/usr/bin/env python3
"""Pass-through statusline hook: records the Claude quota, forwards stdin untouched.

Claude Code invokes the `statusLine` command from settings.json with a JSON
document on stdin. That document carries (once rate limits have been observed):

    "rate_limits": {
      "five_hour": { "used_percentage": 27, "resets_at": 1787803200 },
      "seven_day": { "used_percentage": 24, "resets_at": 1788206400 }
    }

This script is installed *as* the statusLine command and execs the operator's
real one, so their own script stays untouched:

    "statusLine": {
      "type": "command",
      "command": "python3 ~/.claude/herdr-watch-hook.py python3 ~/.claude/statusline.py"
    }

Hard requirement (design D2): the hook is on an interactive path. Any capture
failure is swallowed — stdin is still forwarded, the exit status is the wrapped
command's, and nothing of our own is ever written to stdout.

State file: ~/.config/herdr-watch/claude-usage.json (override: $HERDR_WATCH_USAGE_FILE),
written atomically (temp file in the same directory + rename), shape:

    { "capturedAt": 1787797108,
      "five_hour": { "used_percentage": 27, "resets_at": 1787803200 },
      "seven_day": { "used_percentage": 24, "resets_at": 1788206400 } }

A window absent from the payload is absent here too — never a zero and never a
placeholder reset time. A payload with no usable window leaves the previous
record untouched (normal before the session's first API response).
"""

import json
import os
import subprocess
import sys
import tempfile
import time

DEFAULT_STATE_FILE = os.path.join("~", ".config", "herdr-watch", "claude-usage.json")
WINDOWS = ("five_hour", "seven_day")


def state_file():
    raw = os.environ.get("HERDR_WATCH_USAGE_FILE") or DEFAULT_STATE_FILE
    return os.path.abspath(os.path.expanduser(raw))


def window(raw):
    """Validated window, or None — an unusable window is omitted, never zeroed."""
    if not isinstance(raw, dict):
        return None
    used = raw.get("used_percentage")
    resets = raw.get("resets_at")
    # bool is an int subclass; a boolean here means the shape changed.
    if isinstance(used, bool) or isinstance(resets, bool):
        return None
    if not isinstance(used, int) or not 0 <= used <= 100:
        return None
    if not isinstance(resets, int) or resets <= 0:
        return None
    return {"used_percentage": used, "resets_at": resets}


def record(payload):
    """{capturedAt, <window>...} for the payload, or None when nothing is usable."""
    if not isinstance(payload, dict):
        return None
    limits = payload.get("rate_limits")
    if not isinstance(limits, dict):
        return None
    out = {"capturedAt": int(time.time())}
    for name in WINDOWS:
        w = window(limits.get(name))
        if w is not None:
            out[name] = w
    return out if len(out) > 1 else None


def write_atomically(path, rec):
    """Temp file in the target directory + rename — a reader never sees a partial write."""
    directory = os.path.dirname(path)
    os.makedirs(directory, exist_ok=True)
    fd, tmp = tempfile.mkstemp(dir=directory, prefix=".claude-usage", suffix=".tmp")
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            json.dump(rec, f)
            f.flush()
            os.fsync(f.fileno())
        os.replace(tmp, path)   # atomic on POSIX; last writer wins
        tmp = None
    finally:
        if tmp is not None:
            try:
                os.unlink(tmp)
            except OSError:
                pass


def capture(data):
    """Best-effort capture. Never raises — the statusline must not notice us."""
    try:
        rec = record(json.loads(data.decode("utf-8")))
        if rec is not None:
            write_atomically(state_file(), rec)
    except Exception:
        pass


def main(argv):
    try:
        data = sys.stdin.buffer.read()
    except Exception:
        data = b""

    capture(data)

    cmd = argv[1:]
    if not cmd:
        return 0   # no wrapped command: capture-only mode (used by tests)
    try:
        return subprocess.run(cmd, input=data).returncode
    except OSError:
        return 127   # command not found — same convention as a shell


if __name__ == "__main__":
    sys.exit(main(sys.argv))

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
placeholder reset time. Figures are rounded to whole percents on the way in, so the
file only ever holds integers.

A payload without a usable `five_hour` is ignored entirely: this file is shared by
every Claude Code session on the machine, and older versions emit a `rate_limits`
carrying only `seven_day` with values that disagree with the account's own figures.
Leaving the record untouched beats letting a stale client overwrite a good reading. A payload with no usable window leaves the previous
record untouched (normal before the session's first API response).

A reading that has FALLEN BEHIND the record is ignored too, for the same reason: a
lagging session reports a window that has already reset, or a lower utilization
inside the window the record already covers. Neither can be true of newer figures —
utilization inside a fixed window only grows, and reset times only move forward — so
a reading that goes backwards is a stale client talking, not news.

The file is rewritten ONLY when the figures actually change, so `capturedAt` means
"when these numbers were last seen to move" and can never overstate freshness.
That matters once `statusLine.refreshInterval` is set: those ticks re-run us on a
timer without any API call in between, so the payload carries the same figures as
before — re-stamping them would pass hour-old numbers off as current.
"""

import json
import math
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


def number(value):
    """Finite int/float, or None. bool is an int subclass — a boolean means the shape changed."""
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return None
    return value if math.isfinite(value) else None


def window(raw):
    """Validated window, or None — an unusable window is omitted, never zeroed.

    `used_percentage` is on a 0–100 scale but is NOT always an integer: it is computed
    as a fraction × 100, so a value that isn't whole arrives with floating-point noise
    (observed live: 7.000000000000001). Rejecting floats here silently dropped the
    window, which is why the 5-hour bar used to come and go.

    Above 100 we clamp rather than reject: an overage must not make the gauge vanish
    at exactly the moment it matters most.
    """
    if not isinstance(raw, dict):
        return None
    used = number(raw.get("used_percentage"))
    resets = number(raw.get("resets_at"))
    if used is None or used < 0:
        return None
    if resets is None or resets <= 0:
        return None
    return {"used_percentage": min(100, round(used)), "resets_at": int(resets)}


def windows_of(payload):
    """The validated windows alone (no timestamp), or None when this is not a usable reading.

    A reading MUST carry `five_hour` to count. That is not fussiness — several Claude
    Code sessions of DIFFERENT versions share this one file, and older ones emit
    `rate_limits` with only `seven_day`, whose value agrees neither with the account
    figure nor with each other (observed live: 7.0 / 20 / 26 / 29 against a true 34).
    Accepting those lets an old session overwrite a good record on every tick — which
    is exactly how the 5-hour bar kept vanishing. Requiring the window the old shape
    never has filters them out without needing to track versions.
    """
    if not isinstance(payload, dict):
        return None
    limits = payload.get("rate_limits")
    if not isinstance(limits, dict):
        return None
    out = {}
    for name in WINDOWS:
        w = window(limits.get(name))
        if w is not None:
            out[name] = w
    if "five_hour" not in out:
        return None          # неполное показание — прошлую запись не трогаем
    return out


def read_existing(path):
    """Parsed record, or None — missing, unreadable and corrupt all collapse to None."""
    try:
        with open(path, encoding="utf-8") as f:
            rec = json.load(f)
        return rec if isinstance(rec, dict) else None
    except Exception:
        return None


def unchanged(new, existing):
    """True when the recorded figures already equal these ones."""
    if not isinstance(existing, dict):
        return False
    if not isinstance(existing.get("capturedAt"), int):
        return False          # без времени запись бесполезна — перезапишем
    return all(existing.get(name) == new.get(name) for name in WINDOWS)


def regresses(new, existing):
    """True when this reading is BEHIND the record — an older window, or a step back inside one.

    Observed live over 14 minutes on one machine: sessions of different versions wrote
    5h 22 % / 6 % / 16 % / 33 % in turn, two of them carrying a `resets_at` five hours
    stale — a window that had already reset. Requiring a usable `five_hour` (above)
    stops the bar from vanishing, but not the figures from flapping between sessions.

    Two facts make lagging readings recognisable without tracking versions:
    utilization inside one window only ever grows, and a window's reset time only ever
    moves forward. So a reading that goes backwards on either count is behind, and the
    record it would overwrite is the better one.

    Compared per window and only where both sides have it: a window the payload does
    not carry says nothing about lag.
    """
    if not isinstance(existing, dict):
        return False
    for name in WINDOWS:
        fresh = new.get(name)
        recorded = existing.get(name)
        if not isinstance(fresh, dict) or not isinstance(recorded, dict):
            continue
        resets = number(recorded.get("resets_at"))
        used = number(recorded.get("used_percentage"))
        if resets is None or used is None:
            continue                        # запись битая — сравнивать не с чем
        if fresh["resets_at"] < resets:
            return True                     # окно, которое уже сбросилось
        if fresh["resets_at"] == resets and fresh["used_percentage"] < used:
            return True                     # внутри одного окна утилизация не убывает
    return False


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
        new = windows_of(json.loads(data.decode("utf-8")))
        if new is None:
            return                      # нет rate_limits — прошлую запись не трогаем
        path = state_file()
        existing = read_existing(path)
        if regresses(new, existing):
            # Отставшая сессия: окно уже сброшено или процент откатился назад. Записи
            # не касаемся вовсе — иначе цифры прыгают туда-сюда на каждом тике.
            return
        if unchanged(new, existing):
            # Цифры те же — файл (и его mtime) не трогаем вовсе. capturedAt обязан
            # означать «когда показания в последний раз сдвинулись»: с refreshInterval
            # хук зовут по таймеру, а rate_limits в payload свежее последнего ответа
            # API не становятся. Переставляя время на каждый вызов, мы бы выдавали
            # часовой давности цифры за секундные — и убивали индикатор устаревания.
            return
        write_atomically(path, dict(capturedAt=int(time.time()), **new))
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

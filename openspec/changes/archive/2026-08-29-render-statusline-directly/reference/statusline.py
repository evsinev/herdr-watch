#!/usr/bin/env python3
"""Строка состояния Claude Code.

Claude Code запускает этот скрипт на каждом обновлении строки и подаёт на stdin
JSON; напечатанное на stdout становится строкой состояния.

Вид:
    Opus 5 · high | Ctx [██░░░░░░░░] 185.3k/1.0M (19%) | +1055/-37 | Cost $8.88 · 43m (api 19m) | 5h 43% (2h13m) · 7d 18%

Всё, что приходит в полезной нагрузке, — данные, а не команды: ничего не
исполняется, транскрипт открывается только на чтение. Любое поле может
отсутствовать, быть null или не того типа — сегмент без данных просто выпадает
вместе со своим разделителем, а не ломает строку.
"""

import json
import os
import sys
import time

# ── цвета ────────────────────────────────────────────────────────────────
RESET = "\033[0m"
DIM = "\033[2m"
BOLD = "\033[1m"
CYAN = "\033[36m"
YELLOW = "\033[33m"
GREEN = "\033[32m"
RED = "\033[31m"

BAR_CELLS = 10


def paint(text, *codes):
    return "".join(codes) + text + RESET


# ── безопасное чтение полезной нагрузки ──────────────────────────────────
# Каждое значение может оказаться чем угодно, поэтому тип проверяется на месте,
# а не предполагается.


def as_dict(value):
    return value if isinstance(value, dict) else {}


def as_text(value):
    return value if isinstance(value, str) and value.strip() else None


def as_num(value):
    # bool — подкласс int, но процентом или числом токенов быть не может.
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return None
    return float(value)


def pressure(pct):
    """Общий цвет нагрузки: контекст и каждый лимит красятся одинаково."""
    if pct >= 85:
        return RED
    if pct >= 60:
        return YELLOW
    return GREEN


def fmt_num(value):
    if value >= 1e6:
        return "{:.1f}M".format(value / 1e6)
    if value >= 1e3:
        return "{:.1f}k".format(value / 1e3)
    return str(int(value))


def fmt_left(seconds):
    """Остаток до сброса лимита: 2h13m либо 47m."""
    minutes = int(round(seconds / 60.0))
    if minutes < 1:
        minutes = 1  # пока сброс впереди, «0m» вводит в заблуждение
    hours, minutes = divmod(minutes, 60)
    if hours:
        return "{}h{:02d}m".format(hours, minutes)
    return "{}m".format(minutes)


def fmt_span(seconds):
    """Длительность: 5h01m, 43m либо 18s."""
    seconds = int(seconds)
    if seconds >= 3600:
        return "{}h{:02d}m".format(seconds // 3600, (seconds % 3600) // 60)
    if seconds >= 60:
        return "{}m".format(seconds // 60)
    return "{}s".format(seconds)


# ── запасной путь для контекста ──────────────────────────────────────────


def tokens_from_transcript(path):
    """Сумма токенов последнего запроса из JSONL, если context_window не пришёл.

    Читаем хвост файла: транскрипт бывает большим, а нужна последняя запись с usage.
    """
    if not isinstance(path, str) or not path:
        return None
    try:
        with open(path, "rb") as fh:
            fh.seek(0, os.SEEK_END)
            size = fh.tell()
            tail = min(size, 256 * 1024)
            fh.seek(size - tail)
            data = fh.read().decode("utf-8", "replace")
    except OSError:
        return None

    for line in reversed(data.splitlines()):
        line = line.strip()
        if not line.startswith("{"):
            continue  # обрезанная первая строка хвоста или мусор
        try:
            record = json.loads(line)
        except ValueError:
            continue
        usage = as_dict(as_dict(record.get("message")).get("usage"))
        if not usage:
            continue
        total = 0.0
        found = False
        for key in ("input_tokens", "cache_creation_input_tokens", "cache_read_input_tokens"):
            value = as_num(usage.get(key))
            if value is not None:
                total += value
                found = True
        if found:
            return total
    return None


# ── сегменты ─────────────────────────────────────────────────────────────


def seg_model(payload):
    model = as_dict(payload.get("model"))
    name = as_text(model.get("display_name")) or as_text(model.get("id"))
    if not name:
        return ""
    out = paint(name, CYAN)
    effort = as_text(as_dict(payload.get("effort")).get("level"))
    if effort:
        out += " · " + effort
    if payload.get("fast_mode") is True:
        out += paint(" · fast", YELLOW)
    if as_dict(payload.get("thinking")).get("enabled") is False:
        out += paint(" · no-think", DIM)
    style = as_text(as_dict(payload.get("output_style")).get("name"))
    if style and style != "default":
        out += paint(" · " + style, DIM)
    return out


def seg_context(payload):
    window = as_dict(payload.get("context_window"))

    used = as_num(window.get("total_input_tokens"))
    if used is None:
        used = tokens_from_transcript(payload.get("transcript_path"))
    if used is None:
        return ""

    size = as_num(window.get("context_window_size"))
    if size is None or size <= 0:
        model_id = as_text(as_dict(payload.get("model")).get("id")) or ""
        size = 1000000.0 if "[1m]" in model_id else 200000.0

    pct = as_num(window.get("used_percentage"))
    if pct is None:
        pct = round(used / size * 100)
    pct = max(0, min(100, int(pct)))

    filled = max(0, min(BAR_CELLS, int(round(pct / 100.0 * BAR_CELLS))))
    if pct > 0 and filled == 0:
        filled = 1  # ненулевой расход обязан быть виден

    colour = pressure(pct)
    bar = paint("█" * filled, colour) + paint("░" * (BAR_CELLS - filled), DIM)
    return "Ctx [{}] {}/{} ({})".format(
        bar, fmt_num(used), fmt_num(size), paint("{}%".format(pct), colour)
    )


def seg_lines(payload):
    cost = as_dict(payload.get("cost"))
    added = as_num(cost.get("total_lines_added")) or 0
    removed = as_num(cost.get("total_lines_removed")) or 0
    if added <= 0 and removed <= 0:
        return ""
    return paint("+{}".format(int(added)), GREEN) + paint("/-{}".format(int(removed)), RED)


def seg_cost(payload):
    data = as_dict(payload.get("cost"))
    cost = as_num(data.get("total_cost_usd"))
    if cost is None:
        return ""
    out = paint("Cost", DIM) + " " + paint("${:.2f}".format(cost), BOLD)

    # Длительность — с первой минуты сессии: секунды в строке только шумят.
    wall_ms = as_num(data.get("total_duration_ms"))
    if wall_ms is None or wall_ms < 60000:
        return out

    tail = fmt_span(wall_ms / 1000.0)
    api_ms = as_num(data.get("total_api_duration_ms"))
    if api_ms is not None and api_ms > 0:
        tail += " (api {})".format(fmt_span(api_ms / 1000.0))
    return out + paint(" · " + tail, DIM)


def seg_limits(payload):
    limits = as_dict(payload.get("rate_limits"))  # ключа нет, пока лимиты не наблюдались
    parts = []

    five = as_dict(limits.get("five_hour"))
    pct = as_num(five.get("used_percentage"))
    if pct is not None:
        pct = max(0, min(100, int(round(pct))))
        text = "5h " + paint("{}%".format(pct), pressure(pct))
        resets_at = as_num(five.get("resets_at"))  # epoch-секунды
        if resets_at is not None:
            left = resets_at - time.time()
            if left > 0:
                text += " ({})".format(fmt_left(left))
        parts.append(text)

    seven = as_dict(limits.get("seven_day"))
    pct = as_num(seven.get("used_percentage"))
    if pct is not None:
        pct = max(0, min(100, int(round(pct))))
        parts.append("7d " + paint("{}%".format(pct), pressure(pct)))

    return " · ".join(parts)


def render(payload):
    segments = [
        seg_model(payload),
        seg_context(payload),
        seg_lines(payload),
        seg_cost(payload),
        seg_limits(payload),
    ]
    return paint(" | ", DIM).join(s for s in segments if s)


def main():
    try:
        raw = sys.stdin.read()
    except Exception:
        raw = ""
    try:
        payload = json.loads(raw)
    except Exception:
        payload = None
    if not isinstance(payload, dict):
        payload = {}

    try:
        line = render(payload)
    except Exception:
        # Строка состояния не имеет права ронять интерфейс: лучше пусто, чем трассировка.
        line = ""
    sys.stdout.write(line + "\n")


if __name__ == "__main__":
    main()

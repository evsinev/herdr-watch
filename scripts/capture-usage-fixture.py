#!/usr/bin/env python3
"""Снять одну живую выдачу GET /api/oauth/usage в тестовую фикстуру.

Запускать ВРУЧНУЮ и РОВНО ОДИН РАЗ: эндпоинт наказывает 429 на ~22 минуты, и
повтор внутри штрафного окна его перевзводит.

Токен не печатается и не попадает в argv (а значит, и в `ps`): читается в память
и уходит прямо в заголовок запроса. Креденшл только читается — ничего не пишется,
не обновляется и не удаляется, ровно как в самом herdr-watch.

Выбор креденшла — по СОДЕРЖИМОМУ, а не по порядку в связке: под сервисом
«Claude Code-credentials» обычно лежит несколько элементов, и первый вполне может
оказаться протухшим (именно на этом однажды и обожглись). Берём непротухший, со
скоупом user:profile, а из нескольких подходящих — с самым поздним сроком.

    python3 scripts/capture-usage-fixture.py                 # в фикстуру №2
    python3 scripts/capture-usage-fixture.py --out путь.json
    python3 scripts/capture-usage-fixture.py --force         # перезаписать существующую
"""

import argparse
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request

SERVICE = "Claude Code-credentials"
PROFILE_SCOPE = "user:profile"
ENDPOINT = "https://api.anthropic.com/api/oauth/usage"
DEFAULT_OUT = "backend/src/test/resources/usage-pull/oauth-usage-live-2.json"
FALLBACK_VERSION = "2.1.250"


def repo_root() -> str:
    try:
        out = subprocess.run(["git", "rev-parse", "--show-toplevel"],
                             capture_output=True, text=True, check=True)
        return out.stdout.strip()
    except Exception:
        return os.getcwd()


def keychain_accounts() -> list[str]:
    """Имена аккаунтов под сервисом. dump-keychain БЕЗ -d паролей не печатает."""
    try:
        out = subprocess.run(["security", "dump-keychain", "login.keychain-db"],
                             capture_output=True, text=True, timeout=60)
    except Exception as e:
        print(f"! не удалось прочитать связку: {e}", file=sys.stderr)
        return []

    accounts, block = [], []
    for line in out.stdout.splitlines() + ["keychain: "]:
        if line.startswith("keychain: "):
            text = "\n".join(block)
            if SERVICE in text:
                for b in block:
                    if '"acct"' in b and '=' in b:
                        value = b.split("=", 1)[1].strip()
                        if value.startswith('"') and value.endswith('"'):
                            value = value[1:-1]
                        if value and value != "<NULL>":
                            accounts.append(value)
            block = []
        else:
            block.append(line)
    return list(dict.fromkeys(accounts))


def read_credential(account: str | None) -> dict | None:
    """Содержимое элемента связки. Возвращает разобранный JSON или None."""
    cmd = ["security", "find-generic-password", "-s", SERVICE]
    if account:
        cmd += ["-a", account]
    cmd += ["-w"]
    try:
        out = subprocess.run(cmd, capture_output=True, text=True, timeout=60)
    except Exception:
        return None
    if out.returncode != 0 or not out.stdout.strip():
        return None
    try:
        return json.loads(out.stdout)["claudeAiOauth"]
    except Exception:
        return None


def pick_credential() -> tuple[str, str]:
    """(токен, чем он опознан). Печатаем только срок и скоупы — токен никогда."""
    now_ms = int(time.time() * 1000)
    candidates = []

    accounts = keychain_accounts()
    if not accounts:
        print("· аккаунты в связке не перечислились — пробую элемент по сервису")
        accounts = [None]

    for acct in accounts:
        cred = read_credential(acct)
        if not cred:
            print(f"· {acct or '(первый под сервисом)'}: прочитать не удалось")
            continue
        expires = cred.get("expiresAt")
        scopes = cred.get("scopes") or []
        alive = isinstance(expires, (int, float)) and expires > now_ms
        when = time.strftime("%F %T", time.localtime(expires / 1000)) if expires else "?"
        print(f"· {acct or '(первый под сервисом)'}: истекает {when}"
              f" {'✓ жив' if alive else '✗ протух'}, скоупы {scopes}")
        if alive and PROFILE_SCOPE in scopes and cred.get("accessToken"):
            candidates.append((expires, acct, cred["accessToken"]))

    # Файл — путь для headless-хостов; на macOS обычно отсутствует.
    path = os.path.expanduser("~/.claude/.credentials.json")
    if os.path.exists(path):
        try:
            with open(path) as f:
                cred = json.load(f)["claudeAiOauth"]
            expires, scopes = cred.get("expiresAt"), cred.get("scopes") or []
            if isinstance(expires, (int, float)) and expires > now_ms \
                    and PROFILE_SCOPE in scopes and cred.get("accessToken"):
                candidates.append((expires, path, cred["accessToken"]))
                print(f"· {path}: годится")
        except Exception:
            pass

    if not candidates:
        print("\n! пригодного креденшла нет: нужен непротухший элемент со скоупом "
              f"{PROFILE_SCOPE}. Открой Claude Code, чтобы он обновил токен, и повтори.",
              file=sys.stderr)
        sys.exit(1)

    candidates.sort(key=lambda c: c[0])
    expires, source, token = candidates[-1]
    return token, f"{source} (истекает {time.strftime('%F %T', time.localtime(expires / 1000))})"


def claude_cli_version() -> str:
    try:
        out = subprocess.run(["claude", "--version"], capture_output=True, text=True, timeout=10)
        first = out.stdout.strip().split()
        for token in first:
            if token[:1].isdigit():
                return token
    except Exception:
        pass
    return FALLBACK_VERSION


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out", default=None, help=f"куда сохранить (по умолчанию {DEFAULT_OUT})")
    ap.add_argument("--force", action="store_true", help="перезаписать существующий файл")
    args = ap.parse_args()

    out = args.out or os.path.join(repo_root(), DEFAULT_OUT)
    if os.path.exists(out) and not args.force:
        print(f"! {out} уже есть. Это разовый снимок — лишний запрос стоит 429 на ~22 минуты.\n"
              f"  Если снимок действительно нужен ещё раз: --force", file=sys.stderr)
        sys.exit(1)

    print(f"ищу креденшл под сервисом «{SERVICE}» (только чтение)")
    token, why = pick_credential()
    print(f"выбран: {why}\n")

    version = claude_cli_version()
    print(f"один GET {ENDPOINT}\n  User-Agent: claude-cli/{version} (external, cli)")
    request = urllib.request.Request(ENDPOINT, headers={
        "Authorization": "Bearer " + token,
        "anthropic-beta": "oauth-2025-04-20",
        "Accept": "application/json, text/plain, */*",
        "User-Agent": f"claude-cli/{version} (external, cli)",
    })

    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            body = response.read().decode("utf-8")
            status = response.status
    except urllib.error.HTTPError as e:
        retry = e.headers.get("Retry-After") if e.headers else None
        print(f"\n! HTTP {e.code}", file=sys.stderr)
        if e.code == 429:
            print(f"  rate limit; Retry-After: {retry or '?'} с. НЕ повторяй раньше этого срока —\n"
                  f"  повтор внутри штрафного окна взводит его заново.", file=sys.stderr)
        elif e.code == 401:
            print("  токен отвергнут. Открой Claude Code, чтобы он обновил его, и повтори.",
                  file=sys.stderr)
        elif e.code == 403:
            print(f"  у креденшла нет скоупа {PROFILE_SCOPE} (так отвечают токены "
                  f"от `claude setup-token`).", file=sys.stderr)
        sys.exit(1)
    except Exception as e:
        print(f"\n! запрос не прошёл: {e}", file=sys.stderr)
        sys.exit(1)

    try:
        data = json.loads(body)
    except json.JSONDecodeError as e:
        print(f"\n! HTTP {status}, но тело не разобралось: {e}", file=sys.stderr)
        sys.exit(1)

    os.makedirs(os.path.dirname(out), exist_ok=True)
    with open(out, "w") as f:
        json.dump(data, f, indent=2, ensure_ascii=False, sort_keys=True)
        f.write("\n")

    print(f"\nHTTP {status} → {out}")
    print("\nчто пришло (тело секрета не несёт — креденшл был в запросе, не в ответе):")
    for key in ("five_hour", "seven_day"):
        window = data.get(key)
        if isinstance(window, dict):
            print(f"  {key}: utilization={window.get('utilization')} resets_at={window.get('resets_at')}")
        else:
            print(f"  {key}: {window!r}")
    limits = data.get("limits") or []
    print(f"  limits[]: {len(limits)} записей")
    for entry in limits:
        scope = (entry.get("scope") or {}).get("model") or {}
        name = scope.get("display_name")
        print(f"    kind={entry.get('kind')} percent={entry.get('percent')}"
              + (f" model={name}" if name else ""))
    print(f"  ключей верхнего уровня: {len(data)}")
    print("\nготово — скажи Claude, что фикстура на месте.")


if __name__ == "__main__":
    main()

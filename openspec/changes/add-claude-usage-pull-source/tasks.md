## 1. Pin down the endpoint contract

- [x] 1.1 Capture one full `GET /api/oauth/usage` response on the reference machine and save it as a test fixture (redacting nothing — it carries no secret; the credential is in the request, not the response)
- [x] 1.2 Record the exact types: `five_hour`/`seven_day` `utilization` as float, `resets_at` as ISO-8601 string, `limits[].percent` as int-or-float, `limits[].resets_at` as ISO-8601
- [x] 1.3 Record every `kind` value seen in `limits[]` and confirm `weekly_scoped` entries carry `scope.model.display_name`
      — видены `session`, `weekly_all`, `weekly_scoped`; последний несёт `scope.model.display_name` (`Fable`).
      **Все легаси-ключи `seven_day_opus/sonnet/omelette/cowork/oauth_apps` = `null`** — подтверждает D6:
      `limits[]` единственный источник помодельных окон.
- [x] 1.4 Capture a second fixture at a different time to confirm which keys are stable and which come and go
      — `oauth-usage-live-2.json`, снята на ~16 ч позже первой (`scripts/capture-usage-fixture.py`).
      **Набор ключей верхнего уровня идентичен** (18 из 18, те же типы), легаси-ключи
      `seven_day_<model>` по-прежнему все `null`. Приходит и уходит не состав, а ЗНАЧЕНИЯ:
      у простаивающего окна `resets_at` = `null` (`five_hour: utilization 0.0, resets_at null`;
      в `limits[]` — `kind=session, percent=0, resets_at=null, is_active=false`). То есть
      «окно присутствует» ≠ «окно отчиталось временем сброса»; тест
      `idleWindowArrivesWithoutAResetTime` это фиксирует. **Открытый вопрос** — считать ли
      такое окно неотчитавшимся (сейчас так: бар не рисуется) или показывать 0 % без времени сброса.
- [x] 1.5 Confirm the response shape matches design D6/D7; update design.md if it does not

## 2. Credential source — read-only, unambiguous

Design D4. This is where the original change went wrong; the tests matter more than the code.

- [x] 2.1 Define the credential record: `accessToken`, `expiresAt` (**milliseconds**), `scopes`, and treat everything else as opaque
- [x] 2.2 Keychain source: enumerate **all** items under service `Claude Code-credentials` and select by evidence — unexpired `expiresAt` and `user:profile` in `scopes` — never by store ordering
- [x] 2.3 When several candidates qualify, take the one expiring latest; when none does, report not-configured rather than returning a dead token
- [x] 2.4 File source `~/.claude/.credentials.json` (same JSON shape) for headless hosts, plus a configured override path
- [x] 2.5 Chain the sources: keychain → file → override; an access-denied outcome is reported distinctly from not-found
- [x] 2.6 Assert by test that no code path writes, refreshes or deletes the credential
- [x] 2.7 Tests: two items where the first is expired (**the reference machine's exact shape** — this is the regression test for the original wrong conclusion); only an expired item; an item without `user:profile`; no item; unreadable store; `expiresAt` correctly read as milliseconds, not seconds
- [x] 2.8 Assert by test that the token never reaches a log line, including every error path

## 2a. Defect found while implementing: a stale client corrupts the shared record

Не запланировано; найдено пробой на живой машине и подтверждено дампом payload'ов.
Шесть сессий Claude Code разных версий (2.1.243/245/247 и 2.1.250) пишут в один
state-файл. Старые шлют `rate_limits` **без `five_hour`**, а их `seven_day`
(7.0 / 20 / 26 / 29) не сходится ни с аккаунтным 34, ни между собой. Каждый тик
`refreshInterval` они затирали хорошую запись — отсюда исчезающий 5-часовой бар.

- [x] 2a.1 Принимать показание, только если в нём есть пригодное `five_hour`; иначе не трогать запись вовсе
- [x] 2a.2 Тесты: старая форма игнорируется; старая форма не затирает хорошую запись и не двигает mtime; новое полное показание по-прежнему побеждает
- [x] 2a.3 Переписать тест `test_incomplete_window_is_omitted` — он фиксировал прежнее поведение
- [x] 2a.4 Поправить дизайн-допущение D3 в `add-claude-usage/design.md`: «every session reports the same account-level quota, so last-writer-wins is benign» опровергнуто
- [x] 2a.5 Подтвердить вживую: монитор перестал сообщать о пропаже 5-часового окна
      — пропадать перестало, но 14-минутный сэмплинг state-файла вскрыл ВТОРОЙ фасет
      того же дефекта: запись затирается не пропуском, а неверными цифрами. За 14 минут
      четыре разных показания — 5h 22 / 6 / 16 / 33 при аккаунтных 22, причём у 16 и 33
      `resets_at` на пять часов позади (окно уже сбросилось). Требование `five_hour`
      отсекает старую ФОРМУ, но не старые ЦИФРЫ.

### 2a-bis. Отставшая сессия не откатывает запись назад

- [x] 2a.6 Правило: не принимать показание, у которого окно уже сбросилось (`resets_at`
      раньше записанного), и не принимать падение процента внутри одного и того же окна.
      Опора — два свойства данных, а не версии клиента: утилизация внутри окна не убывает,
      время сброса только движется вперёд. Сравнение поокно и только там, где обе стороны
      несут окно.
- [x] 2a.7 Тесты: показание из уже сброшенного окна игнорируется (запись и mtime не
      тронуты); откат процента внутри окна игнорируется; откат только в недельном окне —
      тоже; рост внутри окна пишется; НАСТОЯЩИЙ сброс (resets_at сдвинулся вперёд) пишется
      даже с падением до нуля; отставшая сессия между двумя хорошими ничего не портит;
      битая запись не «защищает» себя от свежего показания

## 3. HTTP client and the fingerprint opt-in

- [x] 3.1 ~~Add the HTTP client dependency back to `backend/pom.xml`~~ — **не понадобилась.**
      Берём `java.net.http.HttpClient` из JDK: нужен ровно один GET со своими заголовками и
      чтением `Retry-After`, а декларативный REST-клиент здесь только мешал бы. Одной
      зависимостью меньше; native проверяется задачей 10.2.
- [x] 3.2 Create `ClaudeUsagePullConfig` (`@ConfigMapping`): `source`, `impersonate-claude-cli`, poll interval, backoff floor/cap, retry margin, credential override; add the block to `application.yaml`
- [x] 3.3 Build the request: `Authorization: Bearer …`, `anthropic-beta: oauth-2025-04-20`, `Accept: application/json, text/plain, */*`
- [x] 3.4 User-Agent `claude-cli/<version> (external, cli)`, version detected from the installed Claude Code with a pinned fallback
- [x] 3.5 **Fail closed**: with `source` = `pull`/`auto` and `impersonate-claude-cli` = false, the source does not start, logs why, and issues no request
- [x] 3.6 Map outcomes: 200 → parse; 401 → re-read the credential store next tick; 403 → not-authorized, retried rarely; 429 → rate-limited with `Retry-After`; anything else → schema-changed with the status
- [x] 3.7 Tests for each outcome against a stubbed transport, including a 200 that fails to parse

## 4. Poll policy

Design D5. The failure mode is self-inflicted lockout, so test the policy as pure logic.

- [x] 4.1 Implement the policy as a pure function of (config, state) → next delay, with no I/O
- [x] 4.2 A server `Retry-After` overrides our own schedule: wait that long plus the margin, bounded by a sanity ceiling
- [x] 4.3 Exponential backoff from the floor when no `Retry-After` is given, capped
- [x] 4.4 **Assert the cap exceeds the longest observed penalty (~1300 s)** — a cap below it means the source never recovers
- [x] 4.5 Success resets the state to the normal interval
- [x] 4.6 No overlapping requests (`ConcurrentExecution.SKIP`)
- [x] 4.7 Tests: `Retry-After` beats a shorter own schedule; repeated 429 grows the delay; the cap is respected and exceeds the penalty; recovery returns to normal; 403 does not spin at the normal cadence

## 5. Response mapping

- [x] 5.1 Parse `limits[]` in preference to the legacy top-level `seven_day_<model>` keys; fall back to top level only when `limits` is absent or empty
- [x] 5.2 Normalise at the edge: float utilization → integer percent using **the same rounding and 0–100 clamping the statusline hook applies**, so the two sources cannot disagree by convention
- [x] 5.3 Normalise ISO-8601 `resets_at` → epoch seconds
- [x] 5.4 Map `kind` = `session` → five-hour window, `weekly_all` → weekly window, `weekly_scoped` → a per-model entry keyed by `scope.model.display_name`
- [x] 5.5 Carry an unrecognised model name through as-is; never drop it, never map it to an enum
- [x] 5.6 Tests against the group-1 fixtures: both windows plus Fable; no `limits[]` (legacy shape); an unknown model name; a missing window; a malformed response

## 6. Model and Registry

- [x] 6.1 Add `source` (`STATUSLINE` | `ACCOUNT_API`, never null) and `models` (list, empty never null) to `ClaudeUsage`
- [x] 6.2 Make the statusline reader report `source: STATUSLINE` and an empty `models`
- [x] 6.3 `Registry` retains the latest reading **per source** and publishes the most recently observed one (design D2)
- [x] 6.4 Keep suppressing re-publication of an unchanged winning snapshot
- [x] 6.5 Register the new records in `NativeReflectionConfig`
- [x] 6.6 Tests: pull-only; push-only; both, where the older source must not overwrite the newer; one source failing while the other keeps publishing; `source: push` produces byte-identical behaviour to before this change

## 6a. Defect found by live verification: `source` не управлял публикацией

Найдено задачей 9.2 на живом инстансе: при `source: pull` гейдж всё равно показывал
статуслайновые цифры. `ClaudeUsageReader` не смотрел на `source` вовсе — он публиковал
всегда, а конфиг влиял только на то, запускается ли pull. Спека требует обратного:
«Account API selected alone → a recorded statusline reading is not published».

- [x] 6a.1 Гейтить statusline-ридер на `source.usesPush()`: при `pull` он молчит и state-файл не читает (файл не наш — не трогаем его вовсе)
- [x] 6a.2 Тест с профилем `source=pull`: `tick()` не публикует показание, состояние остаётся `NOT_CONFIGURED`

## 6b. Найдено в работе: помодельные окна мигали под `auto`

Включили pull на рабочем инстансе — Fable в гейдже не появился. Причина не в вёрстке:
`models` ехали вместе с победителем по свежести, а statusline пишет на каждое движение
цифр и почти всегда обгоняет пятиминутный опрос. Разбивка показывалась бы секундами
раз в пять минут.

- [x] 6b.1 `Registry` берёт `models` из последнего ПРИГОДНОГО показания `ACCOUNT_API` независимо от того, кто выиграл по свежести; окна, `source` и `capturedAt` остаются за победителем
- [x] 6b.2 Устаревшее показание аккаунт-API моделей не даёт — разбивка исчезает вместе с источником, а не застывает навсегда
- [x] 6b.3 Тесты: разбивка переживает смену победителя; STALE перестаёт её поставлять; обновление разбивки доезжает при неизменном победителе; push-only по-прежнему без моделей; гашение неизменившегося снапшота не сломалось
- [x] 6b.4 Compact-плитка: одна строка «fable 14% · opus 3%» под полосами (не список с полосками — плитку читают через комнату); пустой `models` не рисует ничего
- [x] 6b.5 Дизайн D2, спека и §4a протокола поправлены: «при `source = STATUSLINE` массив всегда пуст» больше неверно

## 7. Wire surfaces

- [x] 7.1 `source` and `models` as additive fields on the `claude_usage` SSE event and `GET /api/claude-usage`
- [x] 7.2 `SnapshotUsage` gains `source` (string) and a `models` array; no field may serialize as `null` in any state
- [x] 7.3 Leave `severityCode` derived from the session and weekly windows only (design D7) — per-model windows must not change what that integer means to existing devices
- [x] 7.4 Regression-test that `/api/v1/snapshot/agents` is unchanged in all three profiles and `protocolVersion` is still `1`
- [x] 7.5 Document the new fields in `docs/api/herdr-watch-snapshot-protocol.md` as a §7-compatible addition; regenerate `docs/api/openapi.yaml`

## 8. Frontend

- [x] 8.1 Add `source` and `models` to `lib/types.ts`
- [x] 8.2 Show the source label next to the capture time in `UsageGauge`, reusing existing muted caption styling — no new hex literals
- [x] 8.3 Render per-model rows under the session/weekly bars, visually subordinate; empty `models` renders nothing
- [x] 8.4 Show the same in the Compact tile, or deliberately omit it there and say why in the component doc
- [x] 8.5 `cd frontend && npm run typecheck` and `npm test`

## 9. Verify against the spec

- [x] 9.1 Default config (`source: push`): no credential read, no outbound request, gauge identical to today — confirm with a network-level check, not just by reading the code
- [x] 9.2 `source: pull` without the fingerprint flag: source refuses to start, reason logged, no request made
- [x] 9.3 `source: pull` with the flag: gauge populates and matches the endpoint's own figures; label reads `account api`
- [x] 9.4 Per-model rows appear and match `limits[]`, including Fable
      — живой прогон против фикстуры №2: `weekly_scoped Fable 14` → `models[0] 14 %`,
      `weekly_all` → 7d, `session` → 5h; из трёх записей `limits[]` не потеряно ничего.
      Незнакомая модель проверена отдельно (стаб отдавал `Мираж-9000` — доехала как есть).
- [x] 9.5 `source: auto`: the fresher reading wins; confirm the older source does not overwrite the newer, and the label follows the winner
- [x] 9.6 Kill the network mid-run: pull degrades to stale, the push reading keeps publishing under `auto`, host frames and health keep updating
- [x] 9.7 Force a 429 and confirm the backoff honours `Retry-After` and eventually recovers, without re-tripping the penalty
- [x] 9.8 Point the credential at an expired token: reports not-configured rather than retrying a dead token; after Claude Code refreshes, recovery needs no restart
- [x] 9.9 Confirm the token appears in no log file at any level

## 10. Build and document

- [x] 10.1 `./mvnw package` and the existing suite green
- [x] 10.2 Native build with GraalVM for JDK 21; confirm the pull source works in the native binary
- [x] 10.3 `README.md`: the three `source` values, what `impersonate-claude-cli` actually does and why it exists, how to remove the credential dependency again
- [x] 10.4 Update `CLAUDE.md`: the `usage/pull/` package, two-source reconciliation, and the two traps worth never repeating — select the credential by account, and keep the backoff cap above the penalty window

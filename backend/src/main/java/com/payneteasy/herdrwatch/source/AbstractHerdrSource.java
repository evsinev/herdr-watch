package com.payneteasy.herdrwatch.source;

import com.payneteasy.herdrwatch.Registry;
import com.payneteasy.herdrwatch.model.HostDef;
import com.payneteasy.herdrwatch.model.Model.Health;
import com.payneteasy.herdrwatch.model.Model.AgentInfo;
import com.payneteasy.herdrwatch.model.Model.WorkspaceInfo;
import com.payneteasy.herdrwatch.model.Model.WorktreeInfo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Общая механика источника herdr: один долгоживущий процесс, внутри которого
 * крутится `while read -t D _; do frame; done` — клиент шлёт «тик» на stdin и получает
 * ровно один кадр NDJSON в ответ (request/response). Это же делает процесс
 * самоубивающимся: пропал клиент (обрыв сети/сон) — нет тиков — read по таймауту
 * выходит, удалённый процесс умирает сам и не копится сиротой на хосте.
 *
 * Прямой перевод bash-скрипта herdr-watch:
 *   - битую строку игнорируем, состояние не трогаем;
 *   - процесс отвалился -> UNREACHABLE + пауза + перезапуск.
 *
 * Различается только КАК запускается процесс (ssh или локальная shell) —
 * это отдаёт наружу {@link #processCommand(String)}. Всё остальное общее.
 *
 * Запускается в виртуальном потоке, поэтому блокирующее чтение здесь идиоматично.
 */
public abstract class AbstractHerdrSource implements Source {

    protected final Logger log = LoggerFactory.getLogger(getClass());
    private static final ObjectMapper MAPPER = new ObjectMapper();

    protected final HostDef cfg;
    protected final Registry registry;

    private volatile boolean running = true;
    private volatile Process proc;      // текущий процесс — чтобы stop() мог его убить
    private volatile Thread worker;     // поток источника — чтобы stop() прервал readLine/sleep
    private Health reported;             // последнее залогированное состояние (только из потока источника)

    protected AbstractHerdrSource(HostDef cfg, Registry registry) {
        this.cfg = cfg;
        this.registry = registry;
    }

    /** Команда запуска процесса-источника, оборачивающая один и тот же frame-скрипт. */
    protected abstract List<String> processCommand(String frameCmd);

    /** Короткое имя вида источника для логов ("ssh" / "local"). */
    protected abstract String kind();

    /** Человекочитаемый таргет для логов ("ssh:<host>" / "local"). */
    protected abstract String target();

    /**
     * Мгновенная и чистая остановка: снимаем флаг, прерываем поток (разблокирует
     * блокирующий readLine и паузу reconnect) и убиваем процесс. Нужно для
     * hot-remove/hot-edit — источник умирает сразу, без ожидания следующего кадра.
     */
    @Override
    public void stop() {
        running = false;
        Thread t = worker;
        if (t != null) t.interrupt();
        Process p = proc;
        if (p != null) p.destroyForcibly();
    }

    @Override
    public void run() {
        worker = Thread.currentThread();
        String frameCmd = buildRemoteCommand();
        log.info("[{}] source starting — target={}, herdr={}, poll={}s, reconnect={}s",
                cfg.id(), target(), cfg.herdrPath(), cfg.pollInterval(), cfg.reconnectDelay());

        while (running) {
            registry.setHealth(cfg.id(), Health.UNREACHABLE);   // аналог update_state "⚪"
            proc = null;
            try {
                List<String> cmd = processCommand(frameCmd);
                if (log.isDebugEnabled()) {
                    // launcher без последнего аргумента (гигантского frame-скрипта)
                    log.debug("[{}] exec: {} <frame>", cfg.id(),
                            String.join(" ", cmd.subList(0, cmd.size() - 1)));
                }
                proc = new ProcessBuilder(cmd)
                        .redirectErrorStream(false)
                        .start();

                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8));
                     OutputStream tick = proc.getOutputStream()) {
                    String line;
                    while (running) {
                        tick.write('\n');               // просим следующий кадр + доказываем, что клиент жив
                        tick.flush();
                        line = r.readLine();
                        if (line == null) break;        // EOF — соединение закрыто, идём на reconnect
                        if (!line.isBlank()) handleFrame(line);
                        if (!running) break;
                        sleepSeconds(cfg.pollInterval()); // темп кадров теперь задаёт клиент
                    }
                }
                if (running) {
                    announce(Health.UNREACHABLE, "[" + cfg.id() + "] connection to " + target()
                            + " closed — retry in " + cfg.reconnectDelay() + "s. " + troubleshoot());
                }
            } catch (Exception e) {
                announce(Health.UNREACHABLE, "[" + cfg.id() + "] cannot reach " + target()
                        + " (" + e + ") — retry in " + cfg.reconnectDelay() + "s. " + troubleshoot());
            } finally {
                Process p = proc;
                if (p != null && p.isAlive()) p.destroyForcibly();
            }

            if (!running) break;
            registry.setHealth(cfg.id(), Health.UNREACHABLE);
            sleepSeconds(cfg.reconnectDelay());
        }
        log.info("[{}] source stopped", cfg.id());
    }

    /**
     * Frame-команда: один кадр JSON на строку.
     *
     * Кадр содержит:
     *   ws — herdr workspace list
     *   ag — herdr agent list
     *   wt — карта { workspace_id -> worktree list } по всем воркспейсам
     *   ts — метка времени
     *
     * worktree собираем в цикле по всем workspace_id (herdr worktree list
     * --workspace <id>). Для не-git воркспейсов вызов вернёт null — это
     * нормально, ключ просто не попадёт в карту.
     */
    private String buildRemoteCommand() {
        String h = cfg.herdrPath();
        // read -t <deadline>: цикл ждёт «тик» от клиента (см. run()) и выдаёт ровно один
        // кадр на тик. Нет тика в течение deadline (клиент отвалился/уснул) или EOF на stdin —
        // read возвращает non-zero, цикл выходит, удалённый процесс умирает сам. Так мы не
        // копим осиротевшие frame-циклы на удалённом хосте при обрыве сети/засыпании.
        int readTimeout = Math.max(cfg.pollInterval() * 3, 15);
        return ""
                + ": herdr-watch-frame-loop;"   // метка для ps/pgrep (ops + scripts/reap-stale-herdr.sh)
                + "frame() {"
                + "  ws=\"$(" + h + " workspace list 2>/dev/null || echo null)\";"
                + "  ag=\"$(" + h + " agent list 2>/dev/null || echo null)\";"
                + "  ids=\"$(printf '%s' \"$ws\" | jq -r '.result.workspaces[]?.workspace_id' 2>/dev/null)\";"
                + "  wt='{}';"
                + "  for id in $ids; do"
                + "    w=\"$(" + h + " worktree list --workspace \"$id\" 2>/dev/null || echo null)\";"
                + "    wt=\"$(printf '%s' \"$wt\" | jq -c --arg id \"$id\" --argjson w \"$w\" '. + {($id): $w}' 2>/dev/null || printf '%s' \"$wt\")\";"
                + "  done;"
                + "  jq -c -n --argjson ws \"$ws\" --argjson ag \"$ag\" --argjson wt \"$wt\""
                + "    '{ws:$ws,ag:$ag,wt:$wt,ts:(now|floor)}';"
                + "};"
                + "while read -t " + readTimeout + " _; do frame; done";
    }

    /** Парсим одну строку NDJSON и кладём в Registry. Битую строку молча пропускаем. */
    private void handleFrame(String line) {
        try {
            JsonNode frame = MAPPER.readTree(line);
            JsonNode wsNode = frame.get("ws");
            JsonNode agNode = frame.get("ag");
            JsonNode wtNode = frame.get("wt");   // карта { workspace_id -> worktree list }
            Long ts = frame.hasNonNull("ts") ? frame.get("ts").asLong() : null;

            boolean herdrOk = wsNode != null && !wsNode.isNull()
                    && agNode != null && !agNode.isNull();

            List<WorkspaceInfo> ws = herdrOk ? parseWorkspaces(wsNode, wtNode) : List.of();
            List<AgentInfo> ag = herdrOk ? parseAgents(agNode) : List.of();

            if (herdrOk) {
                announce(Health.CONNECTED, "[" + cfg.id() + "] connected to " + target()
                        + " — " + ws.size() + " workspace(s), " + ag.size() + " agent(s)");
            } else {
                announce(Health.DEGRADED, "[" + cfg.id() + "] " + target()
                        + " reachable but herdr returned no data — " + troubleshoot());
            }

            registry.applyFrame(cfg.id(), ts, ws, ag, herdrOk);
        } catch (Exception e) {
            // частичная/битая строка — не трогаем состояние (как jq 2>/dev/null)
            log.debug("[{}] bad frame: {}", cfg.id(), e.toString());
        }
    }

    /** Логируем смену состояния соединения ОДИН раз (без спама на каждый кадр/ретрай). */
    private void announce(Health h, String msg) {
        if (reported == h) return;
        reported = h;
        if (h == Health.CONNECTED) log.info(msg);
        else log.warn(msg);
    }

    /** Подсказка «как поправить» в зависимости от вида источника. */
    private String troubleshoot() {
        if ("local".equals(kind())) {
            return "check: herdr installed & in PATH (or set herdr-path), jq installed locally";
        }
        return "check: passwordless SSH to " + cfg.host() + " (BatchMode), host reachable, "
                + "herdr in PATH (or herdr-path), jq installed on the remote";
    }

    /** `.result.workspaces[]` -> WorkspaceInfo, с привязкой worktree'ов из карты wt. */
    private List<WorkspaceInfo> parseWorkspaces(JsonNode node, JsonNode wtMap) {
        List<WorkspaceInfo> out = new ArrayList<>();
        JsonNode arr = node.path("result").path("workspaces");
        if (arr.isArray()) {
            for (JsonNode w : arr) {
                String wsId = text(w, "workspace_id");
                JsonNode wtForWs = (wtMap != null && wsId != null) ? wtMap.get(wsId) : null;
                List<WorktreeInfo> worktrees = parseWorktrees(wtForWs);
                out.add(new WorkspaceInfo(
                        wsId,
                        text(w, "label"),
                        w.hasNonNull("number") ? w.get("number").asInt() : null,
                        text(w, "agent_status"),           // rollup считает сам herdr
                        w.path("focused").asBoolean(false),
                        w.path("pane_count").asInt(0),
                        w.path("tab_count").asInt(0),
                        worktrees
                ));
            }
        }
        return out;
    }

    /** `.result.worktrees[]` -> WorktreeInfo. node — результат worktree list одного воркспейса. */
    private List<WorktreeInfo> parseWorktrees(JsonNode node) {
        List<WorktreeInfo> out = new ArrayList<>();
        if (node == null || node.isNull()) return out;
        JsonNode arr = node.path("result").path("worktrees");
        if (arr.isArray()) {
            for (JsonNode t : arr) {
                out.add(new WorktreeInfo(
                        text(t, "branch"),
                        text(t, "path"),
                        text(t, "label"),
                        t.path("is_detached").asBoolean(false),
                        t.path("is_prunable").asBoolean(false),
                        t.path("is_linked_worktree").asBoolean(false),
                        text(t, "open_workspace_id")
                ));
            }
        }
        return out;
    }

    /** `.result.agents[]` -> AgentInfo. Подпись — terminal_title_stripped. */
    private List<AgentInfo> parseAgents(JsonNode node) {
        List<AgentInfo> out = new ArrayList<>();
        JsonNode arr = node.path("result").path("agents");
        if (arr.isArray()) {
            for (JsonNode a : arr) {
                String paneId = text(a, "pane_id");
                // подпись: terminal_title_stripped -> name -> pane_id
                String title = text(a, "terminal_title_stripped", "name");
                out.add(new AgentInfo(
                        (title != null && !title.isBlank()) ? title : paneId,
                        text(a, "agent"),
                        text(a, "agent_status"),
                        text(a, "workspace_id"),
                        text(a, "tab_id"),
                        paneId,
                        a.path("focused").asBoolean(false),
                        text(a, "cwd")
                ));
            }
        }
        return out;
    }

    /** Достаёт первое непустое строковое поле из перечисленных имён. */
    private static String text(JsonNode node, String... keys) {
        for (String k : keys) {
            JsonNode v = node.get(k);
            if (v != null && !v.isNull()) return v.asText();
        }
        return null;
    }

    private void sleepSeconds(int s) {
        try { Thread.sleep(s * 1000L); } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}

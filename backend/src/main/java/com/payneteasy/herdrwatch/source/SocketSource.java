package com.payneteasy.herdrwatch.source;

import com.payneteasy.herdrwatch.Registry;
import com.payneteasy.herdrwatch.model.HostDef;
import com.payneteasy.herdrwatch.model.Model.AgentInfo;
import com.payneteasy.herdrwatch.model.Model.Health;
import com.payneteasy.herdrwatch.model.Model.WorkspaceInfo;
import com.payneteasy.herdrwatch.model.Model.WorktreeInfo;
import com.payneteasy.herdrwatch.source.HerdrProtocol.HerdrResponse;
import com.payneteasy.herdrwatch.source.HerdrProtocol.Request;
import com.payneteasy.herdrwatch.source.HerdrProtocol.Snapshot;
import com.payneteasy.herdrwatch.source.HerdrProtocol.SnapshotResponse;
import com.payneteasy.herdrwatch.source.HerdrProtocol.WorkspaceRec;
import com.payneteasy.herdrwatch.source.HerdrProtocol.WorktreeParams;
import com.payneteasy.herdrwatch.source.HerdrProtocol.WorktreeRec;
import com.payneteasy.herdrwatch.source.HerdrProtocol.WorktreeResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.EOFException;
import java.io.IOException;
import java.net.ConnectException;
import java.nio.file.NoSuchFileException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Источник данных herdr через ПРЯМОЕ подключение к unix-сокету (socket API, NDJSON) — без spawn'а
 * CLI и без jq. <b>Milestone 1</b>: поллинг — раз в {@code pollInterval} шлём {@code session.snapshot}
 * (+ {@code worktree.list} по воркспейсам) и кладём кадр в Registry через тот же
 * {@link Registry#applyFrame} и ту же health-модель, что и command-режим. Сообщения — типизированные
 * DTO ({@link HerdrProtocol}), без обхода JSON-дерева.
 *
 * <p><b>Одно соединение = один запрос:</b> herdr закрывает соединение сразу после ответа на
 * one-shot методы (persistent-соединение — только у {@code events.subscribe}, это Milestone 2).
 * Поэтому каждый запрос идёт по свежему {@link SocketDuplex}.
 *
 * <p>Намеренно НЕ наследует {@link AbstractHerdrSource}: тот завязан на ProcessBuilder + shell-poller.
 * Дублирование reconnect/health-скелета осознанное — общий транспорт вынесем отдельным шагом.
 */
public class SocketSource implements Source {

    private static final Logger log = LoggerFactory.getLogger(SocketSource.class);

    private final HostDef cfg;
    private final Registry registry;
    private final AtomicLong reqId = new AtomicLong();

    private volatile boolean running = true;
    private volatile Thread worker;
    private volatile SocketDuplex inflight;   // текущее соединение запроса — чтобы stop() его закрыл
    private Health reported;                    // последнее залогированное состояние (только из потока источника)

    public SocketSource(HostDef cfg, Registry registry) {
        this.cfg = cfg;
        this.registry = registry;
    }

    @Override
    public void stop() {
        running = false;
        Thread t = worker;
        if (t != null) t.interrupt();          // разблокирует sleep
        SocketDuplex d = inflight;
        if (d != null) try { d.close(); } catch (IOException ignored) { }   // разблокирует readLine
    }

    @Override
    public void run() {
        worker = Thread.currentThread();
        log.info("[{}] socket source starting — target={}, socket={}, poll={}s, reconnect={}s",
                cfg.id(), target(), SocketDuplex.resolveSocketPath(cfg), cfg.pollInterval(), cfg.reconnectDelay());

        while (running) {
            try {
                pollOnce();
                if (!running) break;
                sleepSeconds(cfg.pollInterval());
            } catch (Exception e) {
                if (!running) break;
                Health h = classify(e);
                announce(h, "[" + cfg.id() + "] " + describe(h, e)
                        + " — retry in " + cfg.reconnectDelay() + "s. " + troubleshoot());
                registry.setHealth(cfg.id(), h);
                sleepSeconds(cfg.reconnectDelay());
            }
        }
        log.info("[{}] socket source stopped", cfg.id());
    }

    /** Один цикл опроса: session.snapshot (+ worktree.list per workspace) → applyFrame. */
    private void pollOnce() throws IOException {
        SnapshotResponse resp = request("session.snapshot", Map.of(), SnapshotResponse.class);
        Snapshot snap = (resp.result() != null) ? resp.result().snapshot() : null;
        boolean herdrOk = snap != null && (snap.workspaces() != null || snap.agents() != null);

        List<AgentInfo> agents = (snap != null && snap.agents() != null)
                ? snap.agents().stream().map(HerdrSnapshotMapper::toAgent).toList()
                : List.of();
        Map<String, List<WorktreeInfo>> wtByWs = worktreesByWorkspace(snap);
        List<WorkspaceInfo> workspaces = (snap != null && snap.workspaces() != null)
                ? snap.workspaces().stream()
                    .map(w -> HerdrSnapshotMapper.toWorkspace(w, wtByWs.getOrDefault(w.workspaceId(), List.of())))
                    .toList()
                : List.of();

        if (herdrOk) {
            announce(Health.CONNECTED, "[" + cfg.id() + "] connected to " + target()
                    + " — " + workspaces.size() + " workspace(s), " + agents.size() + " agent(s)");
        } else {
            announce(Health.DEGRADED, "[" + cfg.id() + "] " + target()
                    + " socket ok but no snapshot data — " + troubleshoot());
        }
        // herdr snapshot без ts — ставим локальное время кадра
        registry.applyFrame(cfg.id(), Instant.now().getEpochSecond(), workspaces, agents, herdrOk);
    }

    /** worktree'ы herdr в снапшот не входят — тянем worktree.list по каждому воркспейсу (как command-режим). */
    private Map<String, List<WorktreeInfo>> worktreesByWorkspace(Snapshot snap) {
        Map<String, List<WorktreeInfo>> map = new HashMap<>();
        if (snap == null || snap.workspaces() == null) return map;
        for (WorkspaceRec w : snap.workspaces()) {
            String wsId = w.workspaceId();
            if (wsId == null) continue;
            try {
                WorktreeResponse resp = request("worktree.list", new WorktreeParams(wsId), WorktreeResponse.class);
                List<WorktreeRec> wts = (resp.result() != null) ? resp.result().worktrees() : null;
                if (wts != null) {
                    map.put(wsId, wts.stream().map(HerdrSnapshotMapper::toWorktree).toList());
                }
            } catch (IOException e) {
                // worktree'ы вторичны — не роняем весь кадр из-за одного воркспейса
                log.debug("[{}] worktree.list {} failed: {}", cfg.id(), wsId, e.toString());
            }
        }
        return map;
    }

    /**
     * Свежее соединение → один типизированный запрос → типизированный ответ. herdr закрывает сокет
     * после ответа (one-shot), поэтому соединение живёт на время одного request.
     */
    private <T extends HerdrResponse> T request(String method, Object params, Class<T> type) throws IOException {
        try (SocketDuplex d = SocketDuplex.open(cfg)) {
            inflight = d;
            String id = "hw-" + reqId.incrementAndGet();
            d.writeLine(HerdrProtocol.MAPPER.writeValueAsString(new Request(id, method, params)));

            for (int i = 0; i < 1000; i++) {
                String line = d.readLine();
                if (line == null) throw new EOFException("herdr socket closed");
                if (line.isBlank()) continue;
                T resp;
                try {
                    resp = HerdrProtocol.MAPPER.readValue(line, type);
                } catch (Exception parse) {
                    continue;   // не наш/битый ответ — пропускаем (в one-shot не должно случаться)
                }
                if (!id.equals(resp.id())) continue;   // чужой ответ / push-строка
                if (resp.error() != null) {
                    throw new IOException("herdr error on " + method + ": " + resp.error().message());
                }
                return resp;
            }
            throw new IOException("no response for " + method);
        } finally {
            inflight = null;
        }
    }

    /** local + «сокета нет / refused» (herdr не запущен, машина ок) → DEGRADED; иначе UNREACHABLE. */
    private Health classify(Exception e) {
        if (cfg.local()) {
            if (e instanceof NoSuchFileException || e instanceof ConnectException) return Health.DEGRADED;
            String m = String.valueOf(e.getMessage()).toLowerCase();
            if (m.contains("refused") || m.contains("no such file")) return Health.DEGRADED;
        }
        return Health.UNREACHABLE;
    }

    private String describe(Health h, Exception e) {
        return (h == Health.DEGRADED ? "herdr socket unavailable at " : "cannot reach ")
                + target() + " (" + e + ")";
    }

    private String target() {
        return cfg.local() ? "local" : "ssh:" + cfg.host();
    }

    private String troubleshoot() {
        if (cfg.local()) {
            return "check: herdr server running (herdr status), socket at " + SocketDuplex.resolveSocketPath(cfg);
        }
        return "check: passwordless SSH to " + cfg.host() + ", socat on remote, herdr server running there";
    }

    /** Логируем смену состояния ОДИН раз (без спама на каждый кадр/ретрай). */
    private void announce(Health h, String msg) {
        if (reported == h) return;
        reported = h;
        if (h == Health.CONNECTED) log.info(msg); else log.warn(msg);
    }

    private void sleepSeconds(int s) {
        try {
            Thread.sleep(s * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

package com.payneteasy.herdrwatch.source;

import com.payneteasy.herdrwatch.Registry;
import com.payneteasy.herdrwatch.model.HostDef;
import com.payneteasy.herdrwatch.model.Model.AgentInfo;
import com.payneteasy.herdrwatch.model.Model.Health;
import com.payneteasy.herdrwatch.model.Model.WorkspaceInfo;
import com.payneteasy.herdrwatch.model.Model.WorktreeInfo;
import com.payneteasy.herdrwatch.source.HerdrProtocol.AckResponse;
import com.payneteasy.herdrwatch.source.HerdrProtocol.EventsSubscribeParams;
import com.payneteasy.herdrwatch.source.HerdrProtocol.HerdrResponse;
import com.payneteasy.herdrwatch.source.HerdrProtocol.Request;
import com.payneteasy.herdrwatch.source.HerdrProtocol.Snapshot;
import com.payneteasy.herdrwatch.source.HerdrProtocol.SnapshotResponse;
import com.payneteasy.herdrwatch.source.HerdrProtocol.Subscription;
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
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Источник данных herdr через ПРЯМОЕ подключение к unix-сокету (socket API, NDJSON) — без spawn'а
 * CLI и без jq. Сообщения типизированы ({@link HerdrProtocol}), без обхода JSON-дерева.
 *
 * <p><b>Гибрид B «событие = триггер, snapshot = истина»:</b> держим persistent-подписку
 * {@code events.subscribe} (событий много типов, см. {@link #SUBSCRIBE_TYPES}); любое событие —
 * лишь «пинок»: троттлим ~{@value #DEBOUNCE_MS}мс и дёргаем {@code session.snapshot}
 * (+ {@code worktree.list}) → {@link Registry#applyFrame}. Плюс страховочный <b>poll-floor</b>:
 * гарантированный ре-snapshot каждые {@code pollInterval}с (level-triggered — на случай пропущенного
 * события/после reconnect и чтобы фоновые статусы обновлялись не медленнее command-режима).
 * Дельты событий в Registry не маппим (нужны sequence/replay — вне скоупа).
 *
 * <p><b>Одно соединение = один запрос:</b> herdr закрывает сокет после ответа на one-shot методы
 * (persistent — только у {@code events.subscribe}). Поэтому snapshot/worktree идут по свежим
 * соединениям, а подписка живёт в своём. Транспорт (local unix / remote ssh+socat) — {@link SocketDuplex}.
 *
 * <p>Намеренно НЕ наследует {@link AbstractHerdrSource} (тот про ProcessBuilder + shell-poller).
 */
public class SocketSource implements Source {

    private static final Logger log = LoggerFactory.getLogger(SocketSource.class);

    private static final long DEBOUNCE_MS = 150;      // троттл: не чаще 1 снапшота на этот интервал
    // страховочный poll-floor берём из cfg.pollInterval() — см. startScheduler()

    /** Type-only подписки (не требуют pane_id) — покрывают все изменения; событие лишь триггерит re-snapshot. */
    private static final String[] SUBSCRIBE_TYPES = {
            "workspace.created", "workspace.updated", "workspace.metadata_updated", "workspace.renamed",
            "workspace.moved", "workspace.closed", "workspace.focused",
            "worktree.created", "worktree.opened", "worktree.removed",
            "tab.created", "tab.closed", "tab.focused", "tab.renamed", "tab.moved",
            "pane.created", "pane.closed", "pane.updated", "pane.focused", "pane.moved", "pane.exited",
            "pane.agent_detected",
            "layout.updated",
    };
    private static final EventsSubscribeParams SUBSCRIBE_PARAMS = new EventsSubscribeParams(
            Arrays.stream(SUBSCRIBE_TYPES).map(Subscription::new).toList());

    private final HostDef cfg;
    private final Registry registry;
    private final AtomicLong reqId = new AtomicLong();

    private volatile boolean running = true;
    private volatile Thread worker;
    private volatile SocketDuplex subDuplex;              // подписочное соединение (события)
    private volatile SocketDuplex inflight;               // текущее one-shot соединение снапшота
    private volatile ScheduledExecutorService scheduler;  // seed / resync / троттл снапшотов
    private ScheduledFuture<?> pending;                    // троттл-таск (под synchronized(this))
    private volatile Health reported;                     // последнее залогированное состояние

    public SocketSource(HostDef cfg, Registry registry) {
        this.cfg = cfg;
        this.registry = registry;
    }

    @Override
    public void stop() {
        running = false;
        Thread t = worker;
        if (t != null) t.interrupt();                    // разблокирует sleep
        SocketDuplex s = subDuplex;
        if (s != null) try { s.close(); } catch (IOException ignored) { }   // разблокирует readLine подписки
        stopScheduler();
        SocketDuplex f = inflight;
        if (f != null) try { f.close(); } catch (IOException ignored) { }
    }

    @Override
    public void run() {
        worker = Thread.currentThread();
        log.info("[{}] socket source starting (subscribe) — target={}, socket={}, poll-floor={}s, reconnect={}s",
                cfg.id(), target(), SocketDuplex.resolveSocketPath(cfg), cfg.pollInterval(), cfg.reconnectDelay());

        while (running) {
            try {
                subscribeLoop();
            } catch (Exception e) {
                if (!running) break;
                Health h = classify(e);
                announce(h, "[" + cfg.id() + "] " + describe(h, e)
                        + " — retry in " + cfg.reconnectDelay() + "s. " + troubleshoot());
                registry.setHealth(cfg.id(), h);
            }
            if (!running) break;
            sleepSeconds(cfg.reconnectDelay());
        }
        log.info("[{}] socket source stopped", cfg.id());
    }

    /** Persistent-подписка: subscribe → ack → seed+resync → читать события как триггеры. */
    private void subscribeLoop() throws IOException {
        try (SocketDuplex sub = SocketDuplex.open(cfg)) {
            subDuplex = sub;
            String id = "hw-sub-" + reqId.incrementAndGet();
            sub.writeLine(HerdrProtocol.MAPPER.writeValueAsString(
                    new Request(id, "events.subscribe", SUBSCRIBE_PARAMS)));
            readAck(sub, id);

            startScheduler();   // seed-снапшот сразу + resync по таймеру

            while (running) {
                String line = sub.readLine();
                if (line == null) throw new EOFException("herdr subscribe socket closed");
                if (line.isBlank()) continue;
                triggerSnapshotThrottled();   // любое событие после ack = «пинок» на re-snapshot
            }
        } finally {
            stopScheduler();
            subDuplex = null;
        }
    }

    private void readAck(SocketDuplex sub, String id) throws IOException {
        for (int i = 0; i < 100; i++) {
            String line = sub.readLine();
            if (line == null) throw new EOFException("herdr closed before subscribe ack");
            if (line.isBlank()) continue;
            AckResponse ack;
            try {
                ack = HerdrProtocol.MAPPER.readValue(line, AckResponse.class);
            } catch (Exception e) {
                continue;
            }
            if (!id.equals(ack.id())) continue;   // не ack (событие без нашего id) — ждём дальше
            if (ack.error() != null) {
                throw new IOException("events.subscribe rejected: " + ack.error().message());
            }
            log.info("[{}] subscribed to herdr events ({} types)", cfg.id(), SUBSCRIBE_TYPES.length);
            return;
        }
        throw new IOException("no subscribe ack");
    }

    // --- планировщик снапшотов (seed / resync / троттл) ---

    private synchronized void startScheduler() {
        stopScheduler();
        ScheduledExecutorService s = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "socket-snap-" + cfg.id());
            t.setDaemon(true);
            return t;
        });
        scheduler = s;
        s.execute(this::fetchSnapshotSafe);   // seed немедленно
        long floor = cfg.pollInterval();      // страховочный poll-floor = pollInterval (события ускоряют сверху)
        s.scheduleWithFixedDelay(this::fetchSnapshotSafe, floor, floor, TimeUnit.SECONDS);
    }

    private synchronized void stopScheduler() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        pending = null;
    }

    /** Троттл: не более одного запланированного снапшота за DEBOUNCE_MS; события в окне коалесцируются. */
    private synchronized void triggerSnapshotThrottled() {
        ScheduledExecutorService s = scheduler;
        if (s == null || s.isShutdown()) return;
        if (pending != null && !pending.isDone()) return;
        pending = s.schedule(this::fetchSnapshotSafe, DEBOUNCE_MS, TimeUnit.MILLISECONDS);
    }

    private void fetchSnapshotSafe() {
        if (!running) return;
        try {
            fetchAndApply();
        } catch (Exception e) {
            if (!running) return;
            Health h = classify(e);
            announce(h, "[" + cfg.id() + "] snapshot failed (" + e + ") — " + troubleshoot());
            registry.setHealth(cfg.id(), h);
        }
    }

    /** Один снапшот: session.snapshot (+ worktree.list per workspace) → applyFrame. */
    private void fetchAndApply() throws IOException {
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
        registry.applyFrame(cfg.id(), Instant.now().getEpochSecond(), workspaces, agents, herdrOk);
    }

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
                log.debug("[{}] worktree.list {} failed: {}", cfg.id(), wsId, e.toString());
            }
        }
        return map;
    }

    /** Свежее соединение → один типизированный запрос → типизированный ответ (herdr закрывает сокет после). */
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
                    continue;
                }
                if (!id.equals(resp.id())) continue;
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

    /** Логируем смену состояния ОДИН раз (без спама на каждый кадр). */
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

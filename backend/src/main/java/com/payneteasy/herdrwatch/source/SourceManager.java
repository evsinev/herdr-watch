package com.payneteasy.herdrwatch.source;

import com.payneteasy.herdrwatch.HostStore;
import com.payneteasy.herdrwatch.Registry;
import com.payneteasy.herdrwatch.model.DataSource;
import com.payneteasy.herdrwatch.model.HostDef;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Поднимает по одному SshSource на каждый enabled-хост и управляет их жизненным
 * циклом на лету (hot (re)connect). Каждый источник живёт в своём виртуальном
 * потоке (Java 21) — блокирующее чтение ssh-стрима здесь идиоматично и дёшево,
 * даже на сотнях хостов.
 *
 * Источник списка хостов — {@link HostStore} (bootstrap из application.yaml,
 * слитый с правками из state-файла), а не напрямую конфиг: CRUD меняет именно store.
 */
@ApplicationScoped
public class SourceManager {

    private static final Logger log = LoggerFactory.getLogger(SourceManager.class);

    @Inject HostStore hostStore;
    @Inject Registry registry;

    private final Map<String, Source> sources = new HashMap<>();
    private ExecutorService executor;

    void onStart(@Observes StartupEvent ev) {
        executor = Executors.newVirtualThreadPerTaskExecutor();
        for (HostDef host : hostStore.all()) {
            if (!host.enabled()) {
                log.info("host {} disabled, skipping", host.id());
                continue;
            }
            startHost(host);
        }
    }

    void onStop(@Observes ShutdownEvent ev) {
        synchronized (this) {
            sources.values().forEach(Source::stop);
            sources.clear();
        }
        if (executor != null) executor.shutdownNow();
    }

    /** Поднять источник для хоста (регистрируем в Registry и запускаем в virtual thread). */
    public synchronized void startHost(HostDef host) {
        if (sources.containsKey(host.id())) {
            log.warn("source for {} already running, skipping start", host.id());
            return;
        }
        registry.register(host.id(), host.host());
        Source src = newSource(host);
        sources.put(host.id(), src);
        executor.submit(src);
        log.info("started {} source for {} ({})", label(host), host.id(), host.host());
    }

    /** Выбор реализации источника по (dataSource × local): command→Local/Ssh, socket→SocketSource. */
    private Source newSource(HostDef host) {
        if (host.dataSource() == DataSource.SOCKET) {
            return new SocketSource(host, registry);
        }
        return host.local() ? new LocalSource(host, registry) : new SshSource(host, registry);
    }

    private static String label(HostDef host) {
        boolean socket = host.dataSource() == DataSource.SOCKET;
        if (host.local()) return socket ? "local-socket" : "local";
        return socket ? "ssh-socket" : "ssh";
    }

    /** Остановить источник и убрать хост из состояния (карточка исчезает в UI). */
    public synchronized void stopHost(String id) {
        Source src = sources.remove(id);
        if (src != null) {
            src.stop();
            log.info("stopped source for {}", id);
        }
        registry.remove(id);
    }

    /** Правка хоста = стоп + старт с новыми параметрами (если он включён). */
    public synchronized void restartHost(HostDef host) {
        stopHost(host.id());
        if (host.enabled()) {
            startHost(host);
        }
    }

    /** Запущен ли сейчас источник для хоста (для отдачи health в /api/servers). */
    public synchronized boolean isRunning(String id) {
        return sources.containsKey(id);
    }
}

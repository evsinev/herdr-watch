package com.payneteasy.herdrwatch.snapshot;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Готовность Snapshot API (§5): до завершения первичной инициализации приложения
 * эндпоинты отвечают {@code 503 not_ready}. Флаг взводится на {@link StartupEvent},
 * который fire-ится до начала обслуживания HTTP.
 */
@ApplicationScoped
public class SnapshotReadiness {

    private final AtomicBoolean ready = new AtomicBoolean(false);

    void onStart(@Observes StartupEvent ev) {
        ready.set(true);
    }

    public boolean isReady() {
        return ready.get();
    }
}

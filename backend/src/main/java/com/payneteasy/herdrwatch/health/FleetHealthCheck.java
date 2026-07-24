package com.payneteasy.herdrwatch.health;

import com.payneteasy.herdrwatch.Registry;
import com.payneteasy.herdrwatch.model.Model.Health;
import com.payneteasy.herdrwatch.model.Model.HostState;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

import java.util.List;

/**
 * Readiness-проверка: приложение всегда UP (это read-only монитор, он живёт даже
 * когда хосты недоступны), но в данных отдаём сводку по фермерству —
 * удобно для healthcheck контейнера и быстрого взгляда через /q/health.
 */
@Readiness
@ApplicationScoped
public class FleetHealthCheck implements HealthCheck {

    @Inject
    Registry registry;

    @Override
    public HealthCheckResponse call() {
        List<HostState> hosts = registry.snapshot();
        long connected = hosts.stream().filter(h -> h.health() == Health.CONNECTED).count();
        long degraded = hosts.stream().filter(h -> h.health() == Health.DEGRADED).count();
        long unreachable = hosts.stream().filter(h -> h.health() == Health.UNREACHABLE).count();
        return HealthCheckResponse.named("fleet")
                .up()
                .withData("hosts", hosts.size())
                .withData("connected", connected)
                .withData("degraded", degraded)
                .withData("unreachable", unreachable)
                .build();
    }
}

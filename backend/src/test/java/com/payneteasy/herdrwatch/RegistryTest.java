package com.payneteasy.herdrwatch;

import com.payneteasy.herdrwatch.model.Model.AgentInfo;
import com.payneteasy.herdrwatch.model.Model.Health;
import com.payneteasy.herdrwatch.model.Model.HostState;
import com.payneteasy.herdrwatch.model.Model.WorkspaceInfo;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Чистые юнит-тесты Registry (без загрузки Quarkus). Только пути, не трогающие
 * инжектируемый Event&lt;FrameApplied&gt; (он null при new Registry()): register /
 * setHealth / remove / applyFrame(herdrOk=false) — DEGRADED-кадр событие не фаерит.
 */
class RegistryTest {

    @Test
    void registerStartsUnreachable() {
        Registry r = new Registry();
        r.register("h1", "host-1");
        HostState h = only(r);
        assertEquals("h1", h.id());
        assertEquals("host-1", h.host());
        assertEquals(Health.UNREACHABLE, h.health());
        assertTrue(h.workspaces().isEmpty());
        assertTrue(h.agents().isEmpty());
    }

    @Test
    void setHealthUpdatesAndIsIdempotent() {
        Registry r = new Registry();
        r.register("h1", "host-1");
        r.setHealth("h1", Health.CONNECTED);
        assertEquals(Health.CONNECTED, only(r).health());
        r.setHealth("h1", Health.CONNECTED); // повторный тот же health не должен падать
        assertEquals(Health.CONNECTED, only(r).health());
    }

    @Test
    void degradedFrameKeepsPreviousSnapshotAndSetsTimestamp() {
        Registry r = new Registry();
        r.register("h1", "host-1");
        // herdrOk=false → DEGRADED, FrameApplied НЕ фаерится (frameEvents тут null — ок)
        r.applyFrame("h1", 1000L, List.<WorkspaceInfo>of(), List.<AgentInfo>of(), false);
        HostState h = only(r);
        assertEquals(Health.DEGRADED, h.health());
        assertEquals(Long.valueOf(1000L), h.lastUpdate());
        assertTrue(h.workspaces().isEmpty());
    }

    @Test
    void removeDropsHost() {
        Registry r = new Registry();
        r.register("h1", "host-1");
        r.remove("h1");
        assertTrue(r.snapshot().isEmpty());
    }

    private static HostState only(Registry r) {
        List<HostState> snap = r.snapshot();
        assertEquals(1, snap.size());
        return snap.get(0);
    }
}

package com.payneteasy.herdrwatch;

import com.payneteasy.herdrwatch.model.Model.AgentInfo;
import com.payneteasy.herdrwatch.model.Model.Health;
import com.payneteasy.herdrwatch.model.Model.HostState;
import com.payneteasy.herdrwatch.model.Model.WorkspaceInfo;
import com.payneteasy.herdrwatch.model.Model.StreamEvent;
import com.payneteasy.herdrwatch.usage.ClaudeUsage;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
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

    // --- квота Claude (не свойство хоста, поэтому живёт рядом с картой хостов) ---

    @Test
    void claudeUsageStartsNotConfigured() {
        Registry r = new Registry();
        assertEquals(ClaudeUsage.State.NOT_CONFIGURED, r.claudeUsage().state());
        assertTrue(r.claudeUsage().windows().isEmpty());
    }

    @Test
    void claudeUsageIsPublishedOnceAndNotRepublishedUnchanged() {
        Registry r = new Registry();
        List<StreamEvent> seen = subscribe(r);

        ClaudeUsage first = ClaudeUsage.ok(1787797108L,
                new ClaudeUsage.Window(27, 1787803200L),
                new ClaudeUsage.Window(24, 1788206400L));
        r.updateClaudeUsage(first);
        r.updateClaudeUsage(ClaudeUsage.ok(1787797108L,      // равный по значению — тишина
                new ClaudeUsage.Window(27, 1787803200L),
                new ClaudeUsage.Window(24, 1788206400L)));
        r.updateClaudeUsage(null);                            // и null тоже

        assertEquals(1, seen.size(), "неизменившийся снапшот повторно не рассылаем: " + seen);
        assertEquals("claude_usage", seen.get(0).type());
        assertSame(first, seen.get(0).data());
        assertEquals(first, r.claudeUsage());
    }

    @Test
    void claudeUsageChangeIsPublished() {
        Registry r = new Registry();
        ClaudeUsage first = ClaudeUsage.ok(1787797108L, new ClaudeUsage.Window(27, 1787803200L), null);
        r.updateClaudeUsage(first);

        List<StreamEvent> seen = subscribe(r);
        r.updateClaudeUsage(first.stale("aged out"));

        assertEquals(1, seen.size());
        assertEquals(ClaudeUsage.State.STALE, r.claudeUsage().state());
        assertEquals(27, r.claudeUsage().windows().fiveHour().usedPercent(), "цифры сохраняются");
    }

    @Test
    void claudeUsageDoesNotLeakIntoTheHostHandshake() {
        // §D5: начальный snapshot остаётся List<HostState> — иначе ломаются клиенты.
        Registry r = new Registry();
        r.register("h1", "host-1");
        r.updateClaudeUsage(ClaudeUsage.ok(1787797108L, new ClaudeUsage.Window(27, 1787803200L), null));

        List<HostState> handshake = r.snapshot();
        assertEquals(1, handshake.size());
        assertEquals("h1", handshake.get(0).id());
    }

    private static List<StreamEvent> subscribe(Registry r) {
        List<StreamEvent> seen = new ArrayList<>();
        r.events().subscribe().with(seen::add);
        return seen;
    }
}

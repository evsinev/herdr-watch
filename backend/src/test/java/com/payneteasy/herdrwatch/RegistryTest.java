package com.payneteasy.herdrwatch;

import com.payneteasy.herdrwatch.model.Model.AgentInfo;
import com.payneteasy.herdrwatch.model.Model.Health;
import com.payneteasy.herdrwatch.model.Model.HostState;
import com.payneteasy.herdrwatch.model.Model.WorkspaceInfo;
import com.payneteasy.herdrwatch.model.Model.StreamEvent;
import com.payneteasy.herdrwatch.usage.ClaudeUsage;

import com.payneteasy.herdrwatch.usage.UsageSource;

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

        ClaudeUsage first = ClaudeUsage.ok(UsageSource.STATUSLINE, 1787797108L,
                new ClaudeUsage.Window(27, 1787803200L),
                new ClaudeUsage.Window(24, 1788206400L), List.of());
        r.updateClaudeUsage(first);
        r.updateClaudeUsage(ClaudeUsage.ok(UsageSource.STATUSLINE, 1787797108L,      // равный по значению — тишина
                new ClaudeUsage.Window(27, 1787803200L),
                new ClaudeUsage.Window(24, 1788206400L), List.of()));
        r.updateClaudeUsage(null);                            // и null тоже

        assertEquals(1, seen.size(), "неизменившийся снапшот повторно не рассылаем: " + seen);
        assertEquals("claude_usage", seen.get(0).type());
        assertSame(first, seen.get(0).data());
        assertEquals(first, r.claudeUsage());
    }

    @Test
    void claudeUsageChangeIsPublished() {
        Registry r = new Registry();
        ClaudeUsage first = ClaudeUsage.ok(UsageSource.STATUSLINE, 1787797108L, new ClaudeUsage.Window(27, 1787803200L), null, List.of());
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
        r.updateClaudeUsage(ClaudeUsage.ok(UsageSource.STATUSLINE, 1787797108L, new ClaudeUsage.Window(27, 1787803200L), null, List.of()));

        List<HostState> handshake = r.snapshot();
        assertEquals(1, handshake.size());
        assertEquals("h1", handshake.get(0).id());
    }

    // --- два источника: побеждает более свежее наблюдение ---

    private static ClaudeUsage reading(UsageSource source, long capturedAt, int fivePercent) {
        return ClaudeUsage.ok(source, capturedAt,
                new ClaudeUsage.Window(fivePercent, 1787803200L), null, List.of());
    }

    @Test
    void freshestObservationWinsRegardlessOfSource() {
        Registry r = new Registry();
        r.updateClaudeUsage(reading(UsageSource.STATUSLINE, 2000L, 11));
        r.updateClaudeUsage(reading(UsageSource.ACCOUNT_API, 3000L, 22));

        assertEquals(UsageSource.ACCOUNT_API, r.claudeUsage().source());
        assertEquals(22, r.claudeUsage().windows().fiveHour().usedPercent());
    }

    @Test
    void anOlderSourceDoesNotOverwriteANewerOne() {
        // Именно то, чего боимся под auto: pull ходит по интервалу, statusline по событиям,
        // и опоздавшее показание не должно откатывать картинку назад.
        Registry r = new Registry();
        r.updateClaudeUsage(reading(UsageSource.STATUSLINE, 3000L, 33));

        List<StreamEvent> seen = subscribe(r);
        r.updateClaudeUsage(reading(UsageSource.ACCOUNT_API, 1000L, 99));

        assertEquals(33, r.claudeUsage().windows().fiveHour().usedPercent());
        assertEquals(UsageSource.STATUSLINE, r.claudeUsage().source());
        assertTrue(seen.isEmpty(), "победитель не изменился — событий быть не должно: " + seen);
    }

    @Test
    void aFailingSourceDoesNotSuppressTheHealthyOne() {
        Registry r = new Registry();
        r.updateClaudeUsage(reading(UsageSource.STATUSLINE, 3000L, 33));
        // pull не смог: показаний нет вовсе (capturedAt == null)
        r.updateClaudeUsage(ClaudeUsage.notConfigured(UsageSource.ACCOUNT_API));

        assertEquals(ClaudeUsage.State.OK, r.claudeUsage().state());
        assertEquals(UsageSource.STATUSLINE, r.claudeUsage().source());
    }

    @Test
    void singleSourceBehavesExactlyAsBefore() {
        Registry r = new Registry();
        List<StreamEvent> seen = subscribe(r);

        ClaudeUsage first = reading(UsageSource.STATUSLINE, 1000L, 11);
        r.updateClaudeUsage(first);
        r.updateClaudeUsage(reading(UsageSource.STATUSLINE, 1000L, 11));   // то же самое
        r.updateClaudeUsage(reading(UsageSource.STATUSLINE, 2000L, 12));   // новое

        assertEquals(2, seen.size(), "рассылаем только изменения: " + seen);
        assertEquals(12, r.claudeUsage().windows().fiveHour().usedPercent());
        assertEquals(first, seen.get(0).data());
    }

    // --- помодельные окна едут отдельно от победителя ---

    private static final List<ClaudeUsage.ModelWindow> FABLE =
            List.of(new ClaudeUsage.ModelWindow("Fable", 14, 1788206399L));

    private static ClaudeUsage accountReading(long capturedAt, int fivePercent,
                                              List<ClaudeUsage.ModelWindow> models) {
        return ClaudeUsage.ok(UsageSource.ACCOUNT_API, capturedAt,
                new ClaudeUsage.Window(fivePercent, 1787803200L), null, models);
    }

    @Test
    void modelsSurviveWhenTheOtherSourceWinsOnFreshness() {
        // Ровно то, что видно вживую: хук пишет чаще пятиминутного опроса и выигрывает,
        // а помодельные окна есть только у аккаунт-API. Без переноса Fable мигал бы.
        Registry r = new Registry();
        r.updateClaudeUsage(accountReading(2000L, 22, FABLE));
        r.updateClaudeUsage(reading(UsageSource.STATUSLINE, 3000L, 33));

        ClaudeUsage published = r.claudeUsage();
        assertEquals(UsageSource.STATUSLINE, published.source(), "победитель по свежести не меняется");
        assertEquals(33, published.windows().fiveHour().usedPercent());
        assertEquals(FABLE, published.models(), "разбивка по моделям должна пережить смену победителя");
    }

    @Test
    void staleAccountReadingStopsSupplyingModels() {
        // Pull сломался — разбивка исчезает вместе с ним, а не застывает навсегда.
        Registry r = new Registry();
        r.updateClaudeUsage(accountReading(2000L, 22, FABLE));
        r.updateClaudeUsage(reading(UsageSource.STATUSLINE, 3000L, 33));
        r.updateClaudeUsage(accountReading(2000L, 22, FABLE).stale("offline"));

        assertEquals(UsageSource.STATUSLINE, r.claudeUsage().source());
        assertTrue(r.claudeUsage().models().isEmpty(), "устаревшие помодельные окна не носим");
    }

    @Test
    void carriedModelsDoNotOverrideTheWinnersOwn() {
        Registry r = new Registry();
        r.updateClaudeUsage(accountReading(3000L, 22, FABLE));

        assertEquals(UsageSource.ACCOUNT_API, r.claudeUsage().source());
        assertEquals(FABLE, r.claudeUsage().models());
    }

    @Test
    void pushOnlyStillPublishesNoModels() {
        // Дефолт продукта: аккаунт-API не запускался вовсе — переносить нечего.
        Registry r = new Registry();
        r.updateClaudeUsage(reading(UsageSource.STATUSLINE, 1000L, 11));

        assertTrue(r.claudeUsage().models().isEmpty());
    }

    @Test
    void carryingModelsStillSuppressesUnchangedRepublication() {
        Registry r = new Registry();
        r.updateClaudeUsage(accountReading(2000L, 22, FABLE));
        r.updateClaudeUsage(reading(UsageSource.STATUSLINE, 3000L, 33));

        List<StreamEvent> seen = subscribe(r);
        r.updateClaudeUsage(reading(UsageSource.STATUSLINE, 3000L, 33));   // тот же тик
        r.updateClaudeUsage(accountReading(2000L, 22, FABLE));             // тот же опрос

        assertTrue(seen.isEmpty(), "ничего не изменилось — событий быть не должно: " + seen);
    }

    @Test
    void newModelFiguresReachTheGaugeWhileTheOtherSourceKeepsWinning() {
        Registry r = new Registry();
        r.updateClaudeUsage(accountReading(2000L, 22, FABLE));
        r.updateClaudeUsage(reading(UsageSource.STATUSLINE, 5000L, 33));

        r.updateClaudeUsage(accountReading(3000L, 22,
                List.of(new ClaudeUsage.ModelWindow("Fable", 15, 1788206399L))));

        assertEquals(UsageSource.STATUSLINE, r.claudeUsage().source());
        assertEquals(15, r.claudeUsage().models().get(0).usedPercent(),
                "обновление разбивки обязано доезжать, даже когда победитель прежний");
    }

    @Test
    void sourceIsNeverNull() {
        Registry r = new Registry();
        assertEquals(UsageSource.NONE, r.claudeUsage().source());
        r.updateClaudeUsage(reading(UsageSource.ACCOUNT_API, 1000L, 5));
        assertEquals(UsageSource.ACCOUNT_API, r.claudeUsage().source());
    }

    private static List<StreamEvent> subscribe(Registry r) {
        List<StreamEvent> seen = new ArrayList<>();
        r.events().subscribe().with(seen::add);
        return seen;
    }
}

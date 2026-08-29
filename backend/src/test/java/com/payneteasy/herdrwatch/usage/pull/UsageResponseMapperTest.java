package com.payneteasy.herdrwatch.usage.pull;

import com.payneteasy.herdrwatch.usage.ClaudeUsage;
import com.payneteasy.herdrwatch.usage.UsageSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Разбор ответа аккаунт-API, в т.ч. против настоящего снятого тела. */
class UsageResponseMapperTest {

    private static final ObjectMapper M = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-08-28T01:00:00Z");

    private static JsonNode json(String s) throws Exception {
        return M.readTree(s);
    }

    /**
     * Вторая живая фикстура, снятая на четыре часа позже первой (задача 1.4).
     * Набор ключей верхнего уровня тот же (18 из 18), легаси-ключи
     * {@code seven_day_<model>} по-прежнему все {@code null} — а вот
     * {@code resets_at} у ПРОСТАИВАЮЩЕГО окна приходит {@code null}
     * ({@code five_hour: utilization 0.0, resets_at null}, в {@code limits[]} —
     * {@code kind=session, percent=0, resets_at=null, is_active=false}).
     *
     * <p>Значит, «окно есть» и «окно отчиталось временем сброса» — разные вещи, и
     * форма это не гарантирует. Сейчас окно без времени сброса считается
     * НЕОТЧИТАВШИМСЯ: выдумывать момент сброса нельзя, а {@code resetsAt = 0}
     * означало бы «сбрасывается прямо сейчас». Недельное окно и помодельные при
     * этом не страдают.
     */
    @Test
    void idleWindowArrivesWithoutAResetTime() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/usage-pull/oauth-usage-live-2.json")) {
            assertNotNull(in, "вторая фикстура должна лежать в test resources");
            ClaudeUsage u = UsageResponseMapper.map(M.readTree(in), NOW);

            assertNotNull(u);
            assertEquals(UsageSource.ACCOUNT_API, u.source());
            assertNull(u.windows().fiveHour(), "простаивающее окно без resets_at — не 0%, а «нет данных»");
            assertEquals(37, u.windows().sevenDay().usedPercent());
            assertEquals(1, u.models().size());
            assertEquals("Fable", u.models().get(0).model());
            assertEquals(14, u.models().get(0).usedPercent());
        }
    }

    @Test
    void mapsTheRealCapturedResponse() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/usage-pull/oauth-usage-live-1.json")) {
            assertNotNull(in, "фикстура должна лежать в test resources");
            ClaudeUsage u = UsageResponseMapper.map(M.readTree(in), NOW);

            assertNotNull(u);
            assertEquals(UsageSource.ACCOUNT_API, u.source());
            assertEquals(ClaudeUsage.State.OK, u.state());
            assertEquals(10, u.windows().fiveHour().usedPercent());
            assertEquals(34, u.windows().sevenDay().usedPercent());
            assertEquals(1, u.models().size());
            assertEquals("Fable", u.models().get(0).model());
            assertEquals(14, u.models().get(0).usedPercent());
            assertTrue(u.models().get(0).resetsAt() > 0, "resets_at должен разобраться из ISO-строки");
        }
    }

    @Test
    void limitsWinOverLegacyTopLevelKeys() throws Exception {
        // На живом аккаунте легаси-ключи занулены, а данные — в limits[].
        ClaudeUsage u = UsageResponseMapper.map(json("""
                { "five_hour": { "utilization": 99.0, "resets_at": "2026-08-28T02:20:00+00:00" },
                  "limits": [ { "kind": "session", "percent": 7,
                                "resets_at": "2026-08-28T02:20:00+00:00" } ] }
                """), NOW);

        assertEquals(7, u.windows().fiveHour().usedPercent(), "limits[] должен побеждать");
    }

    @Test
    void fallsBackToLegacyShapeWhenLimitsAbsent() throws Exception {
        ClaudeUsage u = UsageResponseMapper.map(json("""
                { "five_hour": { "utilization": 7.0, "resets_at": "2026-08-28T02:20:00+00:00" },
                  "seven_day": { "utilization": 34.0, "resets_at": "2026-08-31T20:00:00+00:00" } }
                """), NOW);

        assertEquals(7, u.windows().fiveHour().usedPercent());
        assertEquals(34, u.windows().sevenDay().usedPercent());
        assertTrue(u.models().isEmpty());
    }

    @Test
    void unknownModelIsCarriedThroughNotDropped() throws Exception {
        ClaudeUsage u = UsageResponseMapper.map(json("""
                { "limits": [ { "kind": "weekly_scoped", "percent": 5,
                                "resets_at": "2026-08-31T20:00:00+00:00",
                                "scope": { "model": { "display_name": "Somethingnew" } } } ] }
                """), NOW);

        assertEquals(1, u.models().size());
        assertEquals("Somethingnew", u.models().get(0).model(),
                "набор моделей открыт — незнакомую нельзя терять");
    }

    @Test
    void unknownKindIsIgnoredWithoutFailing() throws Exception {
        ClaudeUsage u = UsageResponseMapper.map(json("""
                { "limits": [ { "kind": "something_new", "percent": 5,
                                "resets_at": "2026-08-31T20:00:00+00:00" },
                              { "kind": "session", "percent": 7,
                                "resets_at": "2026-08-28T02:20:00+00:00" } ] }
                """), NOW);

        assertEquals(7, u.windows().fiveHour().usedPercent());
        assertTrue(u.models().isEmpty());
    }

    @Test
    void missingWindowIsAbsentNotZero() throws Exception {
        ClaudeUsage u = UsageResponseMapper.map(json("""
                { "limits": [ { "kind": "session", "percent": 7,
                                "resets_at": "2026-08-28T02:20:00+00:00" } ] }
                """), NOW);

        assertNotNull(u.windows().fiveHour());
        assertNull(u.windows().sevenDay(), "отсутствующее окно — null, не 0%");
    }

    @Test
    void fractionalPercentUsesTheSameRoundingAsThePushSource() throws Exception {
        ClaudeUsage u = UsageResponseMapper.map(json("""
                { "limits": [ { "kind": "session", "percent": 7.000000000000001,
                                "resets_at": "2026-08-28T02:20:00+00:00" },
                              { "kind": "weekly_all", "percent": 34.6,
                                "resets_at": "2026-08-31T20:00:00+00:00" } ] }
                """), NOW);

        assertEquals(7, u.windows().fiveHour().usedPercent());
        assertEquals(35, u.windows().sevenDay().usedPercent());
    }

    @Test
    void overageIsClampedNotDropped() throws Exception {
        ClaudeUsage u = UsageResponseMapper.map(json("""
                { "limits": [ { "kind": "session", "percent": 104.7,
                                "resets_at": "2026-08-28T02:20:00+00:00" } ] }
                """), NOW);
        assertEquals(100, u.windows().fiveHour().usedPercent());
    }

    @Test
    void unparseableResetTimeDropsThatEntryOnly() throws Exception {
        ClaudeUsage u = UsageResponseMapper.map(json("""
                { "limits": [ { "kind": "session", "percent": 7, "resets_at": "not a date" },
                              { "kind": "weekly_all", "percent": 34,
                                "resets_at": "2026-08-31T20:00:00+00:00" } ] }
                """), NOW);

        assertNull(u.windows().fiveHour(), "время сброса выдумывать нельзя");
        assertEquals(34, u.windows().sevenDay().usedPercent());
    }

    @Test
    void nothingUsableIsNull() throws Exception {
        assertNull(UsageResponseMapper.map(json("{}"), NOW));
        assertNull(UsageResponseMapper.map(json("[]"), NOW));
        assertNull(UsageResponseMapper.map(null, NOW));
        assertNull(UsageResponseMapper.map(json("""
                { "five_hour": null, "seven_day": null, "limits": [] }
                """), NOW));
    }
}

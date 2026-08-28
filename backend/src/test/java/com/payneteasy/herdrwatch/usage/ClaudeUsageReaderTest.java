package com.payneteasy.herdrwatch.usage;

import com.payneteasy.herdrwatch.usage.ClaudeUsage.State;

import com.payneteasy.herdrwatch.usage.UsageSource;

import org.junit.jupiter.api.Test;

import java.util.List;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Юнит-тесты чтения state-файла (без Quarkus). Цифры — реальные, снятые с
 * statusline на референсной машине (см. tasks.md, группа 1).
 */
class ClaudeUsageReaderTest {

    private static final long CAPTURED = 1787797108L;
    private static final long FIVE_RESETS = 1787803200L;
    private static final long SEVEN_RESETS = 1788206400L;

    private static final String BOTH = """
            { "capturedAt": %d,
              "five_hour": { "used_percentage": 27, "resets_at": %d },
              "seven_day": { "used_percentage": 24, "resets_at": %d } }
            """.formatted(CAPTURED, FIVE_RESETS, SEVEN_RESETS);

    @TempDir Path dir;

    private ClaudeUsageReader reader(Duration staleAfter) {
        return ClaudeUsageReader.forTest(dir.resolve("claude-usage.json"), staleAfter);
    }

    private void write(String json) throws IOException {
        Files.writeString(dir.resolve("claude-usage.json"), json, StandardCharsets.UTF_8);
    }

    /** Момент, при котором запись CAPTURED ещё свежая. */
    private static Instant fresh() {
        return Instant.ofEpochSecond(CAPTURED + 60);
    }

    @Test
    void bothWindowsAreRead() throws Exception {
        write(BOTH);
        ClaudeUsage u = reader(Duration.ofMinutes(45)).read(fresh());

        assertEquals(State.OK, u.state());
        assertEquals(CAPTURED, u.capturedAt());
        assertNull(u.error());
        assertEquals(new ClaudeUsage.Window(27, FIVE_RESETS), u.windows().fiveHour());
        assertEquals(new ClaudeUsage.Window(24, SEVEN_RESETS), u.windows().sevenDay());
    }

    @Test
    void oneWindowOnlyLeavesTheOtherAbsentNotZero() throws Exception {
        write("""
                { "capturedAt": %d, "five_hour": { "used_percentage": 27, "resets_at": %d } }
                """.formatted(CAPTURED, FIVE_RESETS));
        ClaudeUsage u = reader(Duration.ofMinutes(45)).read(fresh());

        assertEquals(State.OK, u.state());
        assertNotNull(u.windows().fiveHour());
        assertNull(u.windows().sevenDay(), "отсутствующее окно — null, а не 0%");
    }

    @Test
    void recordWithoutWindowsIsStillOkButEmpty() throws Exception {
        write("{ \"capturedAt\": %d }".formatted(CAPTURED));
        ClaudeUsage u = reader(Duration.ofMinutes(45)).read(fresh());

        assertEquals(State.OK, u.state());
        assertTrue(u.windows().isEmpty());
        assertEquals(UsageSeverity.UNKNOWN, UsageSeverity.of(u), "полосу взять неоткуда");
    }

    @Test
    void incompleteWindowIsDropped() throws Exception {
        write("""
                { "capturedAt": %d,
                  "five_hour": { "used_percentage": 27 },
                  "seven_day": { "used_percentage": 24, "resets_at": %d } }
                """.formatted(CAPTURED, SEVEN_RESETS));
        ClaudeUsage u = reader(Duration.ofMinutes(45)).read(fresh());

        assertNull(u.windows().fiveHour(), "без resets_at время сброса выдумывать нельзя");
        assertNotNull(u.windows().sevenDay());
    }

    @Test
    void fractionalPercentIsRoundedNotDropped() throws Exception {
        // Так приходит от Claude Code, когда значение не ровное: доля × 100 с хвостом.
        write("""
                { "capturedAt": %d,
                  "five_hour": { "used_percentage": 7.000000000000001, "resets_at": %d },
                  "seven_day": { "used_percentage": 34.6, "resets_at": %d } }
                """.formatted(CAPTURED, FIVE_RESETS, SEVEN_RESETS));
        ClaudeUsage u = reader(Duration.ofMinutes(45)).read(fresh());

        assertEquals(State.OK, u.state());
        assertNotNull(u.windows().fiveHour(), "дробное значение не должно ронять окно");
        assertEquals(7, u.windows().fiveHour().usedPercent());
        assertEquals(35, u.windows().sevenDay().usedPercent());
    }

    @Test
    void overageAboveHundredIsClampedNotDropped() throws Exception {
        write("""
                { "capturedAt": %d, "five_hour": { "used_percentage": 104.7, "resets_at": %d } }
                """.formatted(CAPTURED, FIVE_RESETS));
        ClaudeUsage u = reader(Duration.ofMinutes(45)).read(fresh());

        assertNotNull(u.windows().fiveHour());
        assertEquals(100, u.windows().fiveHour().usedPercent());
    }

    @Test
    void negativePercentIsDropped() throws Exception {
        write("""
                { "capturedAt": %d, "five_hour": { "used_percentage": -3, "resets_at": %d } }
                """.formatted(CAPTURED, FIVE_RESETS));
        assertNull(reader(Duration.ofMinutes(45)).read(fresh()).windows().fiveHour());
    }

    @Test
    void missingFileIsNotConfigured() {
        ClaudeUsage u = reader(Duration.ofMinutes(45)).read(fresh());

        assertEquals(State.NOT_CONFIGURED, u.state());
        assertNull(u.capturedAt());
        assertTrue(u.windows().isEmpty());
    }

    @Test
    void garbageFileWithoutPreviousSnapshotIsNotConfigured() throws Exception {
        write("this is not json");
        ClaudeUsage u = reader(Duration.ofMinutes(45)).read(fresh());

        assertEquals(State.NOT_CONFIGURED, u.state());
        assertTrue(u.windows().isEmpty(), "частичных значений быть не должно");
    }

    @Test
    void garbageFileAfterAGoodOneKeepsThePreviousSnapshotStale() throws Exception {
        ClaudeUsageReader r = reader(Duration.ofMinutes(45));
        write(BOTH);
        assertEquals(State.OK, r.read(fresh()).state());

        Files.writeString(dir.resolve("claude-usage.json"), "{ broken");
        touch(CAPTURED + 120);                       // сменили mtime → перечитает
        ClaudeUsage u = r.read(fresh());

        assertEquals(State.STALE, u.state());
        assertEquals(CAPTURED, u.capturedAt());      // цифры прошлого снапшота сохранены
        assertEquals(27, u.windows().fiveHour().usedPercent());
        assertNotNull(u.error());
    }

    @Test
    void deletedFileAfterAGoodOneIsStale() throws Exception {
        ClaudeUsageReader r = reader(Duration.ofMinutes(45));
        write(BOTH);
        assertEquals(State.OK, r.read(fresh()).state());

        Files.delete(dir.resolve("claude-usage.json"));
        ClaudeUsage u = r.read(fresh());

        assertEquals(State.STALE, u.state());
        assertEquals(27, u.windows().fiveHour().usedPercent());
        assertNotNull(u.error());
    }

    @Test
    void agedRecordGoesStaleKeepingFiguresAndCaptureTime() throws Exception {
        write(BOTH);
        ClaudeUsage u = reader(Duration.ofMinutes(45)).read(Instant.ofEpochSecond(CAPTURED + 3600));

        assertEquals(State.STALE, u.state());
        assertEquals(CAPTURED, u.capturedAt());
        assertEquals(27, u.windows().fiveHour().usedPercent());
        assertEquals(24, u.windows().sevenDay().usedPercent());
        assertNotNull(u.error());
    }

    @Test
    void unchangedFileIsNotReparsedButAgeIsRecomputed() throws Exception {
        ClaudeUsageReader r = reader(Duration.ofMinutes(45));
        write(BOTH);
        assertEquals(State.OK, r.read(fresh()).state());
        // Файл не трогали (mtime тот же) — но запись успела состариться сама.
        assertEquals(State.STALE, r.read(Instant.ofEpochSecond(CAPTURED + 3600)).state());
    }

    @Test
    void recordWithoutCapturedAtIsRejected() throws Exception {
        write("{ \"five_hour\": { \"used_percentage\": 27, \"resets_at\": %d } }".formatted(FIVE_RESETS));
        assertEquals(State.NOT_CONFIGURED, reader(Duration.ofMinutes(45)).read(fresh()).state());
    }

    // --- полосы severity ---

    @Test
    void severityBandsFollowTheSharedThresholds() {
        assertEquals(UsageSeverity.OK, UsageSeverity.ofPercent(0));
        assertEquals(UsageSeverity.OK, UsageSeverity.ofPercent(UsageSeverity.WARN_AT - 1));
        assertEquals(UsageSeverity.WARNING, UsageSeverity.ofPercent(UsageSeverity.WARN_AT));
        assertEquals(UsageSeverity.CRITICAL, UsageSeverity.ofPercent(UsageSeverity.CRITICAL_AT));
        assertEquals(UsageSeverity.EXHAUSTED, UsageSeverity.ofPercent(100));
    }

    @Test
    void snapshotSeverityIsTheWorstReportedWindow() {
        ClaudeUsage u = ClaudeUsage.ok(UsageSource.STATUSLINE, CAPTURED,
                new ClaudeUsage.Window(27, FIVE_RESETS),
                new ClaudeUsage.Window(95, SEVEN_RESETS), List.of());
        assertEquals(UsageSeverity.CRITICAL, UsageSeverity.of(u));
        assertEquals(UsageSeverity.UNKNOWN, UsageSeverity.of(ClaudeUsage.none()));
    }

    /** Сдвигаем mtime, чтобы reader увидел изменение файла. */
    private void touch(long epochSeconds) throws IOException {
        Files.setLastModifiedTime(dir.resolve("claude-usage.json"),
                java.nio.file.attribute.FileTime.from(Instant.ofEpochSecond(epochSeconds)));
    }
}

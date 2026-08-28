package com.payneteasy.herdrwatch.usage;

import com.payneteasy.herdrwatch.Registry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

/**
 * Читает state-файл, который пишет statusline-хук, и публикует квоту в {@link Registry}.
 *
 * <p>Дизайн D4: не {@code WatchService}, а дешёвый poll по mtime — {@code stat} на тик,
 * парсинг только при смене mtime. Возраст записи, наоборот, пересчитывается КАЖДЫЙ тик:
 * запись стареет сама по себе, без записи в файл (цифры двигаются только пока открыта
 * сессия Claude Code). Лишних событий это не даёт — {@code Registry} гасит неизменившийся
 * снапшот.
 *
 * <p>Любая ошибка чтения остаётся внутри: хосты, кадры и health к этому файлу отношения
 * не имеют (спека: «Failures MUST NOT affect host frame collection»).
 */
@ApplicationScoped
public class ClaudeUsageReader {

    private static final Logger log = LoggerFactory.getLogger(ClaudeUsageReader.class);

    private final ObjectMapper mapper = new ObjectMapper();

    @Inject ClaudeUsageConfig config;
    @Inject Registry registry;

    Path file;
    Duration staleAfter;

    /** Последний успешно разобранный файл (без учёта возраста) — null, если такого не было. */
    private ClaudeUsage parsed;
    /** mtime, соответствующий {@link #parsed}/последней попытке разбора. */
    private Long lastMtimeMillis;
    /** Причина деградации, если последняя попытка чтения провалилась. */
    private String failure;

    /** Экземпляр без CDI — для юнит-тестов маппинга. */
    static ClaudeUsageReader forTest(Path file, Duration staleAfter) {
        ClaudeUsageReader r = new ClaudeUsageReader();
        r.file = file;
        r.staleAfter = staleAfter;
        return r;
    }

    @PostConstruct
    void init() {
        file = resolvePath(config.stateFile());
        staleAfter = parseDuration(config.staleAfter(), Duration.ofMinutes(45));
        log.info("claude usage: watching {} (stale after {})", file, staleAfter);
    }

    /** Первый снимок сразу на старте, не дожидаясь первого тика планировщика. */
    void onStart(@Observes StartupEvent ev) {
        tick();
    }

    @Scheduled(every = "{herdr-watch.claude-usage.poll-interval}",
               concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void poll() {
        tick();
    }

    /** Один цикл: перечитать при необходимости, пересчитать возраст, отдать в Registry. */
    void tick() {
        try {
            registry.updateClaudeUsage(read(Instant.now()));
        } catch (Exception e) {
            // сюда попадать не должны (read() уже всё ловит), но цикл дашборда важнее нас
            log.warn("claude usage: poll failed: {}", e.toString());
        }
    }

    /** Текущее состояние квоты на момент {@code now}. Не бросает. */
    ClaudeUsage read(Instant now) {
        Long mtime = mtimeMillis();

        if (mtime == null) {
            // Файла нет: до установки хука — норма, после удаления — деградация.
            if (parsed == null) return ClaudeUsage.notConfigured(UsageSource.STATUSLINE);
            failure = "state file is gone: " + file;
            return parsed.stale(failure);
        }

        if (parsed == null || lastMtimeMillis == null || !mtime.equals(lastMtimeMillis)) {
            lastMtimeMillis = mtime;   // и при провале тоже: повторно парсить тот же мусор незачем
            try {
                parsed = parse(Files.readAllBytes(file));
                failure = null;
            } catch (Exception e) {
                failure = "state file unreadable: " + e;
                log.debug("claude usage: {}", failure);
                // Частичных значений не публикуем: либо прошлый снапшот, либо «не настроено».
                return parsed != null
                        ? parsed.stale(failure)
                        : new ClaudeUsage(ClaudeUsage.State.NOT_CONFIGURED, UsageSource.STATUSLINE,
                                          null, failure, new ClaudeUsage.Windows(null, null),
                                          java.util.List.of());
            }
        }

        if (failure != null) {
            return parsed != null ? parsed.stale(failure) : ClaudeUsage.notConfigured(UsageSource.STATUSLINE);
        }

        long age = now.getEpochSecond() - parsed.capturedAt();
        if (age > staleAfter.toSeconds()) {
            return parsed.stale("no fresh reading for " + age + "s (no Claude Code session?)");
        }
        return parsed;
    }

    /** mtime файла в миллисекундах, либо null — файла нет / недоступен. */
    private Long mtimeMillis() {
        try {
            if (!Files.isRegularFile(file)) return null;
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException | SecurityException e) {
            return null;
        }
    }

    /**
     * Разбор записи хука (дизайн D3):
     * <pre>{ "capturedAt": …, "five_hour": { "used_percentage": …, "resets_at": … }, "seven_day": {…} }</pre>
     * Окно с некорректными/неполными полями опускается целиком — ноль и выдуманное
     * время сброса запрещены спекой.
     */
    ClaudeUsage parse(byte[] json) throws IOException {
        JsonNode root = mapper.readTree(json);
        if (root == null || !root.isObject()) throw new IOException("not a JSON object");
        JsonNode captured = root.get("capturedAt");
        if (captured == null || !captured.canConvertToLong() || captured.longValue() <= 0) {
            throw new IOException("missing or invalid capturedAt");
        }
        // Помодельных окон statusline не отдаёт вовсе — пустой список честнее пропуска.
        return ClaudeUsage.ok(UsageSource.STATUSLINE, captured.longValue(),
                window(root.get("five_hour")),
                window(root.get("seven_day")),
                java.util.List.of());
    }

    /**
     * Наш хук пишет уже округлённые целые, но дробные значения принимаем тоже: старый
     * хук против нового бэкенда иначе молча ронял бы окно (именно так пропадал
     * 5-часовой бар — {@code used_percentage} приходит как доля × 100 и при неровном
     * значении несёт хвост вида 7.000000000000001). Выше 100 клампим, а не отбрасываем:
     * перерасход не должен гасить индикатор ровно тогда, когда он важнее всего.
     */
    private static ClaudeUsage.Window window(JsonNode n) {
        if (n == null || !n.isObject()) return null;
        JsonNode used = n.get("used_percentage");
        JsonNode resets = n.get("resets_at");
        if (used == null || !used.isNumber()) return null;
        if (resets == null || !resets.isNumber()) return null;
        Integer percent = Percents.toWhole(used.doubleValue());
        long resetsAt = (long) resets.doubleValue();
        if (percent == null || resetsAt <= 0) return null;
        return new ClaudeUsage.Window(percent, resetsAt);
    }

    // --- утилиты ---

    static Path resolvePath(String raw) {
        String p = raw.trim();
        if (p.startsWith("~")) p = System.getProperty("user.home") + p.substring(1);
        return Path.of(p);
    }

    /** Duration в записи Quarkus ("5s", "15m", "PT1H"). При мусоре — fallback, без падения старта. */
    static Duration parseDuration(String raw, Duration fallback) {
        try {
            String s = raw.trim();
            if (s.isEmpty()) return fallback;
            if (!s.startsWith("P") && !s.startsWith("p")) s = "PT" + s;
            return Duration.parse(s);
        } catch (Exception e) {
            return fallback;
        }
    }
}

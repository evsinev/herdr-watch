package com.payneteasy.herdrwatch.usage.pull;

import com.payneteasy.herdrwatch.usage.ClaudeUsage;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Один GET к аккаунт-API Anthropic. JDK-клиента хватает: нужен ровно один запрос
 * со своими заголовками и чтение {@code Retry-After} — декларативный REST-клиент
 * здесь только мешал бы.
 *
 * <p><b>User-Agent обязателен.</b> Эндпоинт кейит rate-limit бакет на отпечатке
 * клиента, и без него запросы летят в заметно более жёсткий (claude-code#30930).
 * Значение — отпечаток Claude Code, поэтому его отправка включается отдельным
 * осознанным флагом (дизайн D3), а не «заодно».
 */
public class ClaudeUsageApiClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private final HttpClient http;
    private final URI endpoint;
    private final String userAgent;

    public ClaudeUsageApiClient(URI endpoint, String userAgent) {
        this(endpoint, userAgent, HttpClient.newBuilder().connectTimeout(TIMEOUT).build());
    }

    ClaudeUsageApiClient(URI endpoint, String userAgent, HttpClient http) {
        this.endpoint = endpoint;
        this.userAgent = userAgent;
        this.http = http;
    }

    /** Результат обращения: исход + разобранный снапшот (только при OK). */
    public record Fetched(PullOutcome.Result result, ClaudeUsage usage) {}

    public Fetched fetch(ClaudeCredential credential, Instant now) {
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .GET()
                .timeout(TIMEOUT)
                .header("Authorization", "Bearer " + credential.accessToken())
                .header("anthropic-beta", "oauth-2025-04-20")
                .header("Accept", "application/json, text/plain, */*")
                .header("User-Agent", userAgent)
                .build();

        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            // Текст чужого исключения уходит в лог как есть, поэтому вычищаем из него
            // токен: единственное место, где мы не контролируем содержимое detail.
            return new Fetched(PullOutcome.Result.of(PullOutcome.OFFLINE,
                    redact(e.toString(), credential.accessToken())), null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Fetched(PullOutcome.Result.of(PullOutcome.OFFLINE, "interrupted"), null);
        }

        return switch (response.statusCode()) {
            case 200 -> parse(response.body(), now);
            case 401 -> new Fetched(PullOutcome.Result.of(PullOutcome.UNAUTHORIZED,
                    "token rejected; will re-read the credential store"), null);
            case 403 -> new Fetched(PullOutcome.Result.of(PullOutcome.FORBIDDEN,
                    "credential lacks " + ClaudeCredential.PROFILE_SCOPE), null);
            case 429 -> new Fetched(PullOutcome.Result.rateLimited(
                    retryAfter(response), "rate limited"), null);
            default -> new Fetched(PullOutcome.Result.of(PullOutcome.SCHEMA_CHANGED,
                    "HTTP " + response.statusCode()), null);
        };
    }

    private Fetched parse(String body, Instant now) {
        try {
            ClaudeUsage usage = UsageResponseMapper.map(MAPPER.readTree(body), now);
            if (usage == null) {
                return new Fetched(PullOutcome.Result.of(PullOutcome.SCHEMA_CHANGED,
                        "200 with no usable windows"), null);
            }
            return new Fetched(PullOutcome.Result.ok(), usage);
        } catch (IOException e) {
            return new Fetched(PullOutcome.Result.of(PullOutcome.SCHEMA_CHANGED,
                    "200 but unparseable: " + e), null);
        }
    }

    /** Токен не должен просочиться в diagnostics ни одним путём (дизайн D4). */
    static String redact(String detail, String token) {
        if (detail == null || token == null || token.isEmpty()) return detail;
        return detail.replace(token, "***");
    }

    /** Retry-After в дельта-секундах — единственная форма, замеченная у этого эндпоинта. */
    static Duration retryAfter(HttpResponse<?> response) {
        Optional<String> raw = response.headers().firstValue("Retry-After");
        if (raw.isEmpty()) return null;
        try {
            long seconds = Long.parseLong(raw.get().trim());
            return seconds > 0 ? Duration.ofSeconds(seconds) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

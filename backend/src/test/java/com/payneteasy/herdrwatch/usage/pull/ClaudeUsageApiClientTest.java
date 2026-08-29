package com.payneteasy.herdrwatch.usage.pull;

import com.payneteasy.herdrwatch.usage.ClaudeUsage;
import com.payneteasy.herdrwatch.usage.UsageSource;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Исходы одного обращения к аккаунт-API против подставного транспорта (задача 3.7).
 * Живой сети здесь нет и быть не должно: эндпоинт наказывает 429 на десятки минут,
 * а нам нужно проверить именно раскладку статусов по {@link PullOutcome}.
 */
class ClaudeUsageApiClientTest {

    private static final URI ENDPOINT = URI.create("https://api.anthropic.com/api/oauth/usage");
    private static final Instant NOW = Instant.parse("2026-08-28T01:00:00Z");
    private static final String TOKEN = "sk-ant-oat01-SECRET-TOKEN-VALUE";

    private static ClaudeCredential credential() {
        return ClaudeCredential.of(TOKEN,
                NOW.plus(Duration.ofHours(8)).toEpochMilli(),
                List.of(ClaudeCredential.PROFILE_SCOPE, "user:inference"));
    }

    private static String liveBody() throws Exception {
        try (InputStream in = ClaudeUsageApiClientTest.class
                .getResourceAsStream("/usage-pull/oauth-usage-live-1.json")) {
            assertNotNull(in, "фикстура должна лежать в test resources");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static ClaudeUsageApiClient client(StubHttpClient http) {
        return new ClaudeUsageApiClient(ENDPOINT, "claude-cli/2.1.250 (external, cli)", http);
    }

    @Test
    void okParsesTheBody() throws Exception {
        StubHttpClient http = StubHttpClient.responding(200, liveBody(), Map.of());
        ClaudeUsageApiClient.Fetched fetched = client(http).fetch(credential(), NOW);

        assertEquals(PullOutcome.OK, fetched.result().outcome());
        ClaudeUsage usage = fetched.usage();
        assertNotNull(usage);
        assertEquals(UsageSource.ACCOUNT_API, usage.source());
        assertEquals(ClaudeUsage.State.OK, usage.state());
        assertEquals(10, usage.windows().fiveHour().usedPercent());
        assertEquals(34, usage.windows().sevenDay().usedPercent());
        assertEquals("Fable", usage.models().get(0).model());
    }

    @Test
    void okCarriesTheHeadersTheEndpointExpects() throws Exception {
        StubHttpClient http = StubHttpClient.responding(200, liveBody(), Map.of());
        client(http).fetch(credential(), NOW);

        HttpRequest sent = http.lastRequest;
        assertNotNull(sent);
        assertEquals("GET", sent.method());
        assertEquals(ENDPOINT, sent.uri());
        assertEquals(Optional.of("Bearer " + TOKEN), sent.headers().firstValue("Authorization"));
        assertEquals(Optional.of("oauth-2025-04-20"), sent.headers().firstValue("anthropic-beta"));
        assertEquals(Optional.of("application/json, text/plain, */*"),
                sent.headers().firstValue("Accept"));
        assertEquals(Optional.of("claude-cli/2.1.250 (external, cli)"),
                sent.headers().firstValue("User-Agent"));
    }

    @Test
    void twoHundredThatFailsToParseIsSchemaChanged() {
        StubHttpClient http = StubHttpClient.responding(200, "{not json at all", Map.of());
        ClaudeUsageApiClient.Fetched fetched = client(http).fetch(credential(), NOW);

        assertEquals(PullOutcome.SCHEMA_CHANGED, fetched.result().outcome());
        assertNull(fetched.usage(), "частичных значений не публикуем");
        assertTrue(fetched.result().detail().contains("unparseable"));
    }

    @Test
    void twoHundredWithNoUsableWindowsIsSchemaChanged() {
        // Форма поехала: тело валидное, но ни одного пригодного окна — угадывать нечего.
        StubHttpClient http = StubHttpClient.responding(200, "{\"spend\":{}}", Map.of());
        ClaudeUsageApiClient.Fetched fetched = client(http).fetch(credential(), NOW);

        assertEquals(PullOutcome.SCHEMA_CHANGED, fetched.result().outcome());
        assertNull(fetched.usage());
        assertTrue(fetched.result().detail().contains("no usable windows"));
    }

    @Test
    void unauthorizedIsReportedSeparatelySoTheStoreIsRereadNextTick() {
        StubHttpClient http = StubHttpClient.responding(401, "{\"error\":\"expired\"}", Map.of());
        ClaudeUsageApiClient.Fetched fetched = client(http).fetch(credential(), NOW);

        assertEquals(PullOutcome.UNAUTHORIZED, fetched.result().outcome());
        assertNull(fetched.usage());
        assertNull(fetched.result().retryAfter());
    }

    @Test
    void forbiddenNamesTheMissingScope() {
        StubHttpClient http = StubHttpClient.responding(403, "{\"error\":\"forbidden\"}", Map.of());
        ClaudeUsageApiClient.Fetched fetched = client(http).fetch(credential(), NOW);

        assertEquals(PullOutcome.FORBIDDEN, fetched.result().outcome());
        assertTrue(fetched.result().detail().contains(ClaudeCredential.PROFILE_SCOPE));
    }

    @Test
    void rateLimitedCarriesRetryAfter() {
        StubHttpClient http = StubHttpClient.responding(429, "rate limited",
                Map.of("Retry-After", List.of("1300")));
        ClaudeUsageApiClient.Fetched fetched = client(http).fetch(credential(), NOW);

        assertEquals(PullOutcome.RATE_LIMITED, fetched.result().outcome());
        assertEquals(Duration.ofSeconds(1300), fetched.result().retryAfter());
    }

    @Test
    void rateLimitedWithoutUsableRetryAfterFallsBackToOurOwnBackoff() {
        StubHttpClient http = StubHttpClient.responding(429, "rate limited",
                Map.of("Retry-After", List.of("Wed, 21 Oct 2026 07:28:00 GMT")));
        ClaudeUsageApiClient.Fetched fetched = client(http).fetch(credential(), NOW);

        assertEquals(PullOutcome.RATE_LIMITED, fetched.result().outcome());
        assertNull(fetched.result().retryAfter(), "нечисловой Retry-After не должен ронять разбор");
    }

    @Test
    void unexpectedStatusIsSchemaChangedAndKeepsTheStatus() {
        StubHttpClient http = StubHttpClient.responding(502, "<html>bad gateway</html>", Map.of());
        ClaudeUsageApiClient.Fetched fetched = client(http).fetch(credential(), NOW);

        assertEquals(PullOutcome.SCHEMA_CHANGED, fetched.result().outcome());
        assertEquals("HTTP 502", fetched.result().detail());
    }

    @Test
    void networkFailureIsOffline() {
        StubHttpClient http = StubHttpClient.failing(new IOException("connect timed out"));
        ClaudeUsageApiClient.Fetched fetched = client(http).fetch(credential(), NOW);

        assertEquals(PullOutcome.OFFLINE, fetched.result().outcome());
        assertNull(fetched.usage());
    }

    /** Токен не имеет права попасть в detail ни на одном из путей (спека, дизайн D4). */
    @Test
    void noOutcomeLeaksTheToken() throws Exception {
        List<ClaudeUsageApiClient.Fetched> all = List.of(
                client(StubHttpClient.responding(200, liveBody(), Map.of())).fetch(credential(), NOW),
                client(StubHttpClient.responding(200, "{oops", Map.of())).fetch(credential(), NOW),
                client(StubHttpClient.responding(401, TOKEN, Map.of())).fetch(credential(), NOW),
                client(StubHttpClient.responding(403, TOKEN, Map.of())).fetch(credential(), NOW),
                client(StubHttpClient.responding(429, TOKEN,
                        Map.of("Retry-After", List.of("60")))).fetch(credential(), NOW),
                client(StubHttpClient.responding(500, TOKEN, Map.of())).fetch(credential(), NOW),
                client(StubHttpClient.failing(new IOException(TOKEN))).fetch(credential(), NOW));

        for (ClaudeUsageApiClient.Fetched f : all) {
            String detail = String.valueOf(f.result().detail());
            assertFalse(detail.contains(TOKEN), "токен просочился в detail: " + detail);
        }
    }

    // --- подставной транспорт ---

    /** Минимальный {@link HttpClient}: отдаёт заготовленный ответ и запоминает запрос. */
    private static final class StubHttpClient extends HttpClient {

        private final int status;
        private final String body;
        private final Map<String, List<String>> headers;
        private final IOException failure;
        private HttpRequest lastRequest;

        private StubHttpClient(int status, String body,
                               Map<String, List<String>> headers, IOException failure) {
            this.status = status;
            this.body = body;
            this.headers = headers;
            this.failure = failure;
        }

        static StubHttpClient responding(int status, String body,
                                         Map<String, List<String>> headers) {
            return new StubHttpClient(status, body, headers, null);
        }

        static StubHttpClient failing(IOException failure) {
            return new StubHttpClient(0, null, Map.of(), failure);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> HttpResponse<T> send(HttpRequest request,
                                        HttpResponse.BodyHandler<T> handler) throws IOException {
            lastRequest = request;
            if (failure != null) throw failure;
            return (HttpResponse<T>) new StubResponse(request, status, body, headers);
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request, HttpResponse.BodyHandler<T> handler,
                HttpResponse.PushPromiseHandler<T> pushHandler) {
            throw new UnsupportedOperationException();
        }

        @Override public Optional<CookieHandler> cookieHandler() { return Optional.empty(); }
        @Override public Optional<Duration> connectTimeout() { return Optional.empty(); }
        @Override public Redirect followRedirects() { return Redirect.NEVER; }
        @Override public Optional<ProxySelector> proxy() { return Optional.empty(); }
        @Override public javax.net.ssl.SSLContext sslContext() {
            throw new UnsupportedOperationException();
        }
        @Override public javax.net.ssl.SSLParameters sslParameters() {
            throw new UnsupportedOperationException();
        }
        @Override public Optional<Authenticator> authenticator() { return Optional.empty(); }
        @Override public Version version() { return Version.HTTP_1_1; }
        @Override public Optional<Executor> executor() { return Optional.empty(); }
    }

    private record StubResponse(HttpRequest request, int status, String body,
                                Map<String, List<String>> rawHeaders) implements HttpResponse<String> {

        @Override public int statusCode() { return status; }
        @Override public HttpRequest request() { return request; }
        @Override public Optional<HttpResponse<String>> previousResponse() { return Optional.empty(); }
        @Override public HttpHeaders headers() {
            return HttpHeaders.of(rawHeaders, (a, b) -> true);
        }
        @Override public String body() { return body; }
        @Override public Optional<javax.net.ssl.SSLSession> sslSession() { return Optional.empty(); }
        @Override public URI uri() { return request.uri(); }
        @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
    }
}

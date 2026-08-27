package com.payneteasy.herdrwatch;

import com.atlassian.oai.validator.restassured.OpenApiValidationFilter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.payneteasy.herdrwatch.model.Model.AgentInfo;
import com.payneteasy.herdrwatch.model.Model.Health;
import com.payneteasy.herdrwatch.model.Model.WorkspaceInfo;
import com.payneteasy.herdrwatch.model.Model.WorktreeInfo;
import com.payneteasy.herdrwatch.usage.ClaudeUsage;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.filter.Filter;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Тесты Snapshot API (§6 плана). Состояние сеется прямо в {@link Registry}
 * (в %test источники отключены → Registry пуст), чистится в {@link #cleanup()}.
 * sequence монотонен между тестами, поэтому в golden-сравнении нормализуется.
 */
@QuarkusTest
class SnapshotResourceTest {

    private static final ObjectMapper M = new ObjectMapper();
    private static final String AGENTS = "/api/v1/snapshot/agents";
    private static final String USAGE = "/api/v1/snapshot/usage";
    private static final List<String> SEEDED = List.of("hostA", "hostB", "hostC", "tie-a", "tie-b");

    @Inject Registry registry;

    @BeforeEach
    void seed() {
        // Чистый лист: на дев-машине может крутиться реальный local-источник (state-файл
        // ~/.config/herdr-watch/hosts.json, который %test не отключает) и наполнять Registry.
        // Источники вызывают только applyFrame (computeIfPresent → no-op после remove), а
        // register() зовётся лишь раз на старте, поэтому удалённые хосты назад не возвращаются.
        registry.snapshot().forEach(h -> registry.remove(h.id()));

        // hostA — BLOCKED(4), linked worktree.
        registry.register("hostA", "hostA");
        registry.applyFrame("hostA", 1000L,
                List.of(new WorkspaceInfo("w1", "auth", 1, "blocked", true, 1, 1,
                        List.of(new WorktreeInfo("feat/auth", "/home/dev/worktrees/paynet-ui/auth",
                                "auth", false, false, true, "w1")))),
                List.of(new AgentInfo("Auth task", "claude", "blocked", "w1", "w1:t1", "w1:p1", true,
                        "/home/dev/worktrees/paynet-ui/auth")),
                true);

        // hostB — WORKING(2), основной чекаут (linked=false).
        registry.register("hostB", "hostB");
        registry.applyFrame("hostB", 1000L,
                List.of(new WorkspaceInfo("w2", "paynet-forms", 1, "working", true, 1, 1,
                        List.of(new WorktreeInfo("main", "/home/dev/src/paynet-forms",
                                "paynet-forms", false, false, false, "w2")))),
                List.of(new AgentInfo("Forms", "codex", "working", "w2", "w2:t1", "w2:p1", true,
                        "/home/dev/src/paynet-forms")),
                true);

        // hostC — IDLE(1), detached HEAD, нераспознанный агент, затем хост стал UNREACHABLE.
        registry.register("hostC", "hostC");
        registry.applyFrame("hostC", 1000L,
                List.of(new WorkspaceInfo("w3", "esp32", 1, "idle", false, 1, 1,
                        List.of(new WorktreeInfo("a1b2c3d4e5f6", "/Users/dev/worktrees/herdr-watch/esp32",
                                "esp32", true, true, true, "w3")))),
                List.of(new AgentInfo("ESP", "", "idle", "w3", "w3:t1", "w3:p1", false,
                        "/Users/dev/worktrees/herdr-watch/esp32")),
                true);
        registry.setHealth("hostC", Health.UNREACHABLE);
    }

    @AfterEach
    void cleanup() {
        SEEDED.forEach(registry::remove);
        registry.updateClaudeUsage(ClaudeUsage.notConfigured());   // квота — общее состояние
    }

    // --- golden per profile (§3.5): состав полей фиксирован → тест исчерпывающий ---

    @Test
    void goldenFull() throws Exception {
        assertMatchesGolden("full", "full.json");
    }

    @Test
    void goldenCompact() throws Exception {
        assertMatchesGolden("compact", "compact.json");
    }

    @Test
    void goldenStatus() throws Exception {
        assertMatchesGolden("status", "status.json");
    }

    private void assertMatchesGolden(String view, String goldenFile) throws Exception {
        String raw = given().when().get(AGENTS + "?view=" + view)
                .then().statusCode(200)
                .extract().asString();
        JsonNode actual = M.readTree(raw);
        assertTrue(actual.get("sequence").isIntegralNumber(), "sequence должен быть числом");
        ((ObjectNode) actual).put("sequence", 0);   // нормализуем монотонный счётчик

        JsonNode expected;
        try (var in = getClass().getResourceAsStream("/snapshot/" + goldenFile)) {
            expected = M.readTree(in);
        }
        assertEquals(expected, actual, "ответ профиля " + view + " разошёлся с golden " + goldenFile);
    }

    // --- заголовки времени (§2) ---

    @Test
    void agentsBodyHasNoTimeFields() {
        // §2: в теле /agents времени нет. Проверяем отсутствие типичных ключей времени.
        given().when().get(AGENTS).then().statusCode(200)
                .body("$", notNullValue())
                .body("serverTime", equalTo(null))
                .body("serverEpoch", equalTo(null))
                .body("timeZone", equalTo(null));
    }

    @Test
    void agentsHasDateAndCacheHeaders() {
        given().when().get(AGENTS).then().statusCode(200)
                .header("Date", notNullValue())
                .header("ETag", notNullValue())
                .header("Cache-Control", equalTo("no-store"));
    }

    // --- условные запросы / ETag (§3.10) ---

    @Test
    void ifNoneMatchReturns304WithoutBody() {
        String etag = given().when().get(AGENTS).then().statusCode(200)
                .extract().header("ETag");

        given().header("If-None-Match", etag).when().get(AGENTS)
                .then().statusCode(304)
                .header("ETag", equalTo(etag))
                .header("Date", notNullValue())         // §2: Date есть и в 304
                .body(emptyOrNullString());
    }

    @Test
    void viewChangeAtSameSequenceReturns200Not304() {
        // ETag учитывает view (§3.10): тот же sequence, другой профиль → 200, не 304.
        String fullEtag = given().when().get(AGENTS + "?view=full").then().statusCode(200)
                .extract().header("ETag");

        given().header("If-None-Match", fullEtag).when().get(AGENTS + "?view=compact")
                .then().statusCode(200);
    }

    // --- валидация параметров (§3.1, §5) ---

    @Test
    void limitOutOfRangeIs400() {
        given().when().get(AGENTS + "?limit=999").then().statusCode(400)
                .body("error", equalTo("invalid_parameter"));
        given().when().get(AGENTS + "?limit=0").then().statusCode(400)
                .body("error", equalTo("invalid_parameter"));
        given().when().get(AGENTS + "?limit=abc").then().statusCode(400)
                .body("error", equalTo("invalid_parameter"));
    }

    @Test
    void unknownViewIs400() {
        given().when().get(AGENTS + "?view=weird").then().statusCode(400)
                .body("error", equalTo("invalid_parameter"));
    }

    @Test
    void viewIsCaseInsensitive() {
        given().when().get(AGENTS + "?view=FULL").then().statusCode(200)
                .body("agents[0].agentDisplay", notNullValue());
    }

    // --- проекция: detached HEAD, hostStale, нераспознанный агент, счётчиков нет ---

    @Test
    void detachedHeadPutsShortShaInBranch() {
        given().when().get(AGENTS).then().statusCode(200)
                .body("agents.find { it.host == 'hostC' }.branch", equalTo("a1b2c3d"))
                .body("agents.find { it.host == 'hostC' }.detachedHead", equalTo(true))
                .body("agents.find { it.host == 'hostC' }.prunable", equalTo(true));
    }

    @Test
    void unreachableHostIsStaleWithLastKnownState() {
        given().when().get(AGENTS).then().statusCode(200)
                .body("agents.find { it.host == 'hostC' }.hostStale", equalTo(true))
                // последнее известное состояние сохранено:
                .body("agents.find { it.host == 'hostC' }.worktreePath",
                        equalTo("/Users/dev/worktrees/herdr-watch/esp32"))
                .body("agents.find { it.host == 'hostC' }.statusCode", equalTo(1));
    }

    @Test
    void noGitCounterFieldsAnywhere() {
        // git-счётчики убраны из контракта (herdr 0.7.4 их не отдаёт).
        given().when().get(AGENTS).then().statusCode(200)
                .body("agents[0].aheadCommits", equalTo(null))
                .body("agents[0].behindCommits", equalTo(null))
                .body("agents[0].gitStale", equalTo(null));
    }

    // --- сортировка (§3.9): при равном statusCode host по возрастанию ---

    @Test
    void sortTieBreakByHostAscending() {
        registry.register("tie-b", "tie-b");
        registry.applyFrame("tie-b", 1000L, List.of(),
                List.of(new AgentInfo("t", "codex", "working", "x", "x:t", "x:p", false, "/tmp/b")), true);
        registry.register("tie-a", "tie-a");
        registry.applyFrame("tie-a", 1000L, List.of(),
                List.of(new AgentInfo("t", "codex", "working", "x", "x:t", "x:p", false, "/tmp/a")), true);

        List<String> hosts = given().when().get(AGENTS).then().statusCode(200)
                .extract().jsonPath().getList("agents.host");
        // оба WORKING(2), tie-a должен идти раньше tie-b
        assertTrue(hosts.indexOf("tie-a") < hosts.indexOf("tie-b"),
                "при равном statusCode host сортируется по возрастанию: " + hosts);
    }

    // --- limit усекает после сортировки (§3.9) ---

    @Test
    void limitTruncatesAfterSortKeepingMostUrgent() {
        given().when().get(AGENTS + "?limit=1").then().statusCode(200)
                .body("agentCount", equalTo(1))
                .body("agentTotal", equalTo(3))
                .body("agents[0].host", equalTo("hostA"))   // BLOCKED(4) — самый срочный
                .body("agents[0].statusCode", equalTo(4));
    }

    // --- /time (§4) ---

    @Test
    void timeEndpointReturnsZoneAndOffset() {
        given().when().get("/api/v1/snapshot/time").then().statusCode(200)
                .header("Date", notNullValue())
                .body("protocolVersion", equalTo(1))
                .body("serverTime", notNullValue())
                .body("serverEpoch", notNullValue())
                .body("timeZone", notNullValue())
                .body("utcOffset", notNullValue());
    }

    // --- квота Claude (§9, дизайн D7/D8) ---

    @Test
    void usageReportsBothWindowsWithSeverityAndCaptureTime() {
        registry.updateClaudeUsage(ClaudeUsage.ok(1787797108L,
                new ClaudeUsage.Window(27, 1787803200L),
                new ClaudeUsage.Window(95, 1788206400L)));

        given().when().get(USAGE).then().statusCode(200)
                .header("Date", notNullValue())
                .header("Cache-Control", equalTo("no-store"))
                .body("protocolVersion", equalTo(1))
                .body("state", equalTo("OK"))
                .body("severityCode", equalTo(3))          // худшее из окон: 95% → critical
                .body("capturedAt", equalTo(1787797108))
                .body("windows.size()", equalTo(2))
                .body("windows.find { it.type == 'five_hour' }.usedPercent", equalTo(27))
                .body("windows.find { it.type == 'five_hour' }.resetsAt", equalTo(1787803200))
                .body("windows.find { it.type == 'seven_day' }.usedPercent", equalTo(95));
    }

    @Test
    void absentWindowIsOmittedFromTheArrayNotZeroed() {
        registry.updateClaudeUsage(ClaudeUsage.ok(1787797108L,
                new ClaudeUsage.Window(27, 1787803200L), null));

        given().when().get(USAGE).then().statusCode(200)
                .body("windows.size()", equalTo(1))
                .body("windows[0].type", equalTo("five_hour"));
    }

    @Test
    void notConfiguredIsASuccessfulEmptyAnswerNotAnError() {
        registry.updateClaudeUsage(ClaudeUsage.notConfigured());

        given().when().get(USAGE).then().statusCode(200)
                .body("state", equalTo("NOT_CONFIGURED"))
                .body("severityCode", equalTo(0))
                .body("capturedAt", equalTo(0))            // §3.4: не null, а 0
                .body("windows.size()", equalTo(0));
    }

    @Test
    void staleSnapshotKeepsItsFiguresAndCaptureTime() {
        registry.updateClaudeUsage(ClaudeUsage.ok(1787797108L,
                new ClaudeUsage.Window(27, 1787803200L), null).stale("aged out"));

        given().when().get(USAGE).then().statusCode(200)
                .body("state", equalTo("STALE"))
                .body("capturedAt", equalTo(1787797108))
                .body("windows[0].usedPercent", equalTo(27));
    }

    @Test
    void noFieldIsEverNull() throws Exception {
        // §3.4 запрещает null в любом состоянии — проверяем все три.
        List<ClaudeUsage> states = List.of(
                ClaudeUsage.notConfigured(),
                ClaudeUsage.ok(1787797108L, null, null),                       // ни одного окна
                ClaudeUsage.ok(1787797108L, new ClaudeUsage.Window(27, 1787803200L),
                        new ClaudeUsage.Window(24, 1788206400L)).stale("aged out"));
        for (ClaudeUsage u : states) {
            registry.updateClaudeUsage(u);
            JsonNode body = M.readTree(given().when().get(USAGE).then().statusCode(200)
                    .extract().asString());
            assertNoNulls(body, "$");
        }
    }

    private static void assertNoNulls(JsonNode node, String path) {
        assertTrue(!node.isNull(), "null по пути " + path);
        if (node.isObject()) {
            node.fields().forEachRemaining(e -> assertNoNulls(e.getValue(), path + "." + e.getKey()));
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) assertNoNulls(node.get(i), path + "[" + i + "]");
        }
    }

    @Test
    void usageEtagIsCapturedAtAndSupportsConditionalRequests() {
        registry.updateClaudeUsage(ClaudeUsage.ok(1787797108L,
                new ClaudeUsage.Window(27, 1787803200L), null));

        String etag = given().when().get(USAGE).then().statusCode(200)
                .extract().header("ETag");
        assertEquals("\"usage-1787797108\"", etag);

        given().header("If-None-Match", etag).when().get(USAGE)
                .then().statusCode(304)
                .header("ETag", equalTo(etag))
                .header("Date", notNullValue())
                .body(emptyOrNullString());

        // Новая запись → новый валидатор → снова полное тело.
        registry.updateClaudeUsage(ClaudeUsage.ok(1787799999L,
                new ClaudeUsage.Window(31, 1787803200L), null));
        given().header("If-None-Match", etag).when().get(USAGE).then().statusCode(200);
    }

    @Test
    void usageDoesNotDisturbTheAgentsEndpoint() {
        // §7: добавление эндпоинта не меняет ни один профиль и не двигает версию.
        registry.updateClaudeUsage(ClaudeUsage.ok(1787797108L,
                new ClaudeUsage.Window(27, 1787803200L),
                new ClaudeUsage.Window(24, 1788206400L)));

        for (String view : List.of("full", "compact", "status")) {
            given().when().get(AGENTS + "?view=" + view).then().statusCode(200)
                    .body("protocolVersion", equalTo(1))
                    .body("agents[0].usedPercent", equalTo(null))
                    .body("agents[0].severityCode", equalTo(null))
                    .body("severityCode", equalTo(null))
                    .body("windows", equalTo(null))
                    .body("capturedAt", equalTo(null));
        }
    }

    // --- валидация обоих эндпоинтов против сгенерированной docs/api/openapi.yaml ---

    @Test
    void responsesConformToOpenApiSpec() {
        Filter validation = new OpenApiValidationFilter("../docs/api/openapi.yaml");
        for (String view : List.of("full", "compact", "status")) {
            Response r = given().filter(validation).when().get(AGENTS + "?view=" + view);
            assertEquals(200, r.statusCode(), "ответ view=" + view + " не соответствует openapi.yaml: " + r.asString());
        }
        Response t = given().filter(validation).when().get("/api/v1/snapshot/time");
        assertEquals(200, t.statusCode(), "ответ /time не соответствует openapi.yaml: " + t.asString());
        Response u = given().filter(validation).when().get(USAGE);
        assertEquals(200, u.statusCode(), "ответ /usage не соответствует openapi.yaml: " + u.asString());
    }
}

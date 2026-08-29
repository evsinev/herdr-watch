package com.payneteasy.herdrwatch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.payneteasy.herdrwatch.model.Model.StreamEvent;
import com.payneteasy.herdrwatch.usage.ClaudeUsage;
import com.payneteasy.herdrwatch.usage.UsageSource;

import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.mutiny.helpers.test.AssertSubscriber;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Задача 7.1: {@code source} и {@code models} — ДОБАВЛЕННЫЕ поля на обеих внутренних
 * поверхностях: SSE-событие {@code claude_usage} и {@code GET /api/claude-usage}.
 * Обе берут один и тот же {@link Registry#claudeUsage()}, поэтому проверяем именно
 * то, что уезжает по проводу, а не форму record'а.
 */
@QuarkusTest
class ClaudeUsageWireTest {

    private static final ObjectMapper M = new ObjectMapper();
    private static final String REST = "/api/claude-usage";

    @Inject Registry registry;

    /**
     * Registry держит последнее показание КАЖДОГО источника, а на дев-машине живой
     * statusline-ридер вполне мог успеть положить своё. Гасим оба слота показанием
     * без времени наблюдения — такое заведомо проигрывает любому засеянному.
     */
    @BeforeEach
    void neutraliseRealReadings() {
        registry.updateClaudeUsage(ClaudeUsage.notConfigured(UsageSource.STATUSLINE));
        registry.updateClaudeUsage(ClaudeUsage.notConfigured(UsageSource.ACCOUNT_API));
        registry.updateClaudeUsage(ClaudeUsage.none());
    }

    /**
     * Гасим ВСЕ слоты, а не только NONE: @QuarkusTest делит одно приложение на весь
     * прогон, и показание с временем наблюдения, оставленное в чужом слоте, выиграет
     * уже в другом тестовом классе (ровно так и всплыло на полном прогоне).
     */
    @AfterEach
    void cleanup() {
        for (UsageSource s : UsageSource.values()) {
            registry.updateClaudeUsage(ClaudeUsage.notConfigured(s));
        }
        registry.updateClaudeUsage(ClaudeUsage.none());
    }

    private static ClaudeUsage accountApiReading(long capturedAt) {
        return ClaudeUsage.ok(UsageSource.ACCOUNT_API, capturedAt,
                new ClaudeUsage.Window(10, 1787803200L),
                new ClaudeUsage.Window(34, 1788206400L),
                List.of(new ClaudeUsage.ModelWindow("Fable", 14, 1788206399L)));
    }

    @Test
    void restCarriesSourceAndModels() {
        registry.updateClaudeUsage(accountApiReading(1787797108L));

        given().when().get(REST).then().statusCode(200)
                .body("state", equalTo("OK"))
                .body("source", equalTo("ACCOUNT_API"))
                .body("capturedAt", equalTo(1787797108))
                .body("windows.fiveHour.usedPercent", equalTo(10))
                .body("windows.sevenDay.usedPercent", equalTo(34))
                .body("models.size()", equalTo(1))
                .body("models[0].model", equalTo("Fable"))
                .body("models[0].usedPercent", equalTo(14))
                .body("models[0].resetsAt", equalTo(1788206399));
    }

    @Test
    void restNeverOmitsTheFieldsEvenWithNothingToReport() {
        // Пустое — не «нет поля»: клиент не должен различать «моделей нет» и «поле не пришло».
        registry.updateClaudeUsage(ClaudeUsage.none());

        given().when().get(REST).then().statusCode(200)
                .body("state", equalTo("NOT_CONFIGURED"))
                .body("source", equalTo("NONE"))
                .body("models.size()", equalTo(0));
    }

    @Test
    void statuslineReadingReportsItsOwnSourceAndNoModels() {
        registry.updateClaudeUsage(ClaudeUsage.ok(UsageSource.STATUSLINE, 1787797200L,
                new ClaudeUsage.Window(27, 1787803200L), null, List.of()));

        given().when().get(REST).then().statusCode(200)
                .body("source", equalTo("STATUSLINE"))
                .body("models.size()", equalTo(0));
    }

    @Test
    void sseEventCarriesTheSameAdditiveFields() throws Exception {
        AssertSubscriber<StreamEvent> sub = registry.events()
                .subscribe().withSubscriber(AssertSubscriber.create(4));

        registry.updateClaudeUsage(accountApiReading(1787797300L));

        StreamEvent event = sub.awaitNextItems(1).getItems().get(0);
        assertEquals("claude_usage", event.type());

        JsonNode payload = M.valueToTree(event.data());
        assertEquals("ACCOUNT_API", payload.get("source").asText());
        assertTrue(payload.get("models").isArray());
        assertEquals(1, payload.get("models").size());
        assertEquals("Fable", payload.get("models").get(0).get("model").asText());
        assertEquals(14, payload.get("models").get(0).get("usedPercent").asInt());

        // Добавка не сдвинула то, что клиенты уже читали.
        assertEquals("OK", payload.get("state").asText());
        assertEquals(1787797300L, payload.get("capturedAt").asLong());
        assertEquals(10, payload.get("windows").get("fiveHour").get("usedPercent").asInt());
        sub.cancel();
    }

    @Test
    void sseAndRestAgreeFieldForField() throws Exception {
        AssertSubscriber<StreamEvent> sub = registry.events()
                .subscribe().withSubscriber(AssertSubscriber.create(4));

        registry.updateClaudeUsage(accountApiReading(1787797400L));

        // Через строку с обеих сторон: valueToTree дал бы LongNode там, где readTree
        // даёт IntNode, и сравнение развалилось бы на типе узла, а не на содержимом.
        JsonNode fromSse = M.readTree(
                M.writeValueAsString(sub.awaitNextItems(1).getItems().get(0).data()));
        JsonNode fromRest = M.readTree(given().when().get(REST).then().statusCode(200)
                .extract().asString());

        assertEquals(fromSse, fromRest, "SSE и REST обязаны отдавать одно и то же тело");
        sub.cancel();
    }

    /** Токен не проходит через модель квоты вовсе — проверяем, что и не появился. */
    @Test
    void quotaPayloadCarriesNoCredentialMaterial() {
        registry.updateClaudeUsage(accountApiReading(1787797500L));
        String body = given().when().get(REST).then().statusCode(200).extract().asString();

        assertNotNull(body);
        for (String forbidden : List.of("accessToken", "refreshToken", "Bearer", "sk-ant")) {
            assertFalse(body.contains(forbidden), "в теле квоты не место '" + forbidden + "'");
        }
    }
}

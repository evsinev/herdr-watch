package com.payneteasy.herdrwatch.usage;

import com.payneteasy.herdrwatch.Registry;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Выбор источника — это выбор того, ЧТО публикуется, а не только того, кто опрашивает.
 * Спека: «Account API selected alone → a recorded statusline reading is not published».
 * Поймано живой проверкой (группа 9): ридер игнорировал {@code source} и публиковал
 * всегда, из-за чего при {@code source: pull} гейдж показывал статуслайновые цифры.
 */
@QuarkusTest
@TestProfile(StatuslineSourceModeTest.PullOnly.class)
class StatuslineSourceModeTest {

    public static class PullOnly implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            // impersonate-claude-cli не включаем: pull обязан не стартовать (fail closed),
            // и никакого исходящего запроса тест не делает.
            return Map.of("herdr-watch.claude-usage.source", "pull");
        }
    }

    @Inject ClaudeUsageReader reader;
    @Inject Registry registry;

    @Test
    void statuslineReadingIsNotPublishedWhenOnlyTheAccountApiIsSelected() {
        for (UsageSource s : UsageSource.values()) {
            registry.updateClaudeUsage(ClaudeUsage.notConfigured(s));
        }
        registry.updateClaudeUsage(ClaudeUsage.none());

        reader.tick();   // на дев-машине настоящий state-файл существует и читается

        assertNotEquals(UsageSource.STATUSLINE, registry.claudeUsage().source(),
                "при source=pull показание statusline публиковаться не должно");
        assertEquals(ClaudeUsage.State.NOT_CONFIGURED, registry.claudeUsage().state());
    }
}

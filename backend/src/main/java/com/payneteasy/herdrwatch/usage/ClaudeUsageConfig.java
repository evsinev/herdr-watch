package com.payneteasy.herdrwatch.usage;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.util.Optional;

/**
 * Настройки чтения квоты Claude. Ключ "herdr-watch.claude-usage" в application.yaml.
 *
 * <p>Значения дублируются в application.yaml (а не только здесь через {@link WithDefault}),
 * потому что {@code poll-interval} подставляется в {@code @Scheduled(every = "{...}")} —
 * это разрешение обычного config-свойства, а не маппинга.
 */
@ConfigMapping(prefix = "herdr-watch.claude-usage")
public interface ClaudeUsageConfig {

    /** State-файл, который пишет statusline-хук (scripts/herdr-watch-statusline-hook.py). */
    @WithDefault("~/.config/herdr-watch/claude-usage.json")
    String stateFile();

    /** Период проверки mtime state-файла (Duration-строка). */
    @WithDefault("5s")
    String pollInterval();

    /** Возраст записи, после которого она считается STALE (Duration-строка). */
    @WithDefault("45m")
    String staleAfter();

    /**
     * Откуда берём квоту: {@code push} (statusline-хук, дефолт), {@code pull}
     * (аккаунт-API) или {@code auto} (оба, побеждает более свежее наблюдение).
     *
     * <p>Дефолт остаётся push намеренно: главное свойство отгруженного дизайна —
     * ноль креденшлов и ноль исходящих запросов, и оно не должно исчезать само собой.
     */
    @WithDefault("push")
    SourceMode source();

    Pull pull();

    enum SourceMode {
        PUSH, PULL, AUTO;

        public boolean usesPush() { return this == PUSH || this == AUTO; }
        public boolean usesPull() { return this == PULL || this == AUTO; }
    }

    /** Настройки pull-источника. Действуют только при source = pull|auto. */
    interface Pull {

        /**
         * Слать User-Agent Claude Code — без него запросы попадают в заметно более
         * жёсткий rate-limit бакет (claude-code#30930). Это выдача себя за
         * first-party клиент, поэтому решение отдельное и по умолчанию выключено:
         * без него pull-источник не стартует вовсе (дизайн D3).
         */
        @WithDefault("false")
        boolean impersonateClaudeCli();

        @WithDefault("https://api.anthropic.com/api/oauth/usage")
        String endpoint();

        @WithDefault("Claude Code-credentials")
        String keychainService();

        @WithDefault("~/.claude/.credentials.json")
        String credentialsFile();

        /** Версия для User-Agent; не задана — определить по установленному Claude Code. */
        Optional<String> claudeCliVersion();

        /** Период опроса в здоровом состоянии. */
        @WithDefault("5m")
        String pollInterval();

        /** Первый шаг бэкоффа после отказа. */
        @WithDefault("1m")
        String backoffFloor();

        /**
         * Потолок бэкоффа. ОБЯЗАН превышать самое длинное наблюдаемое наказание (~1300 с),
         * иначе источник никогда из него не выберется (дизайн D5).
         */
        @WithDefault("2h")
        String backoffCap();

        /** Запас поверх серверного Retry-After, чтобы не попасть на край окна. */
        @WithDefault("30s")
        String retryMargin();
    }
}

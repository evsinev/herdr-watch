package com.payneteasy.herdrwatch.usage;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

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
}

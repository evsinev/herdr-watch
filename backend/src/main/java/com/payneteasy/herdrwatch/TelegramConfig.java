package com.payneteasy.herdrwatch;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.util.Optional;

/**
 * Настройки Telegram-оповещений. Ключ "herdr-watch.telegram" в application.yaml,
 * значения обычно приходят из env (TELEGRAM_*). Секреты в state-файл/UI не пишем.
 */
@ConfigMapping(prefix = "herdr-watch.telegram")
public interface TelegramConfig {

    /** Мастер-выключатель оповещений. */
    @WithDefault("false")
    boolean enabled();

    /** Токен бота (от @BotFather). */
    Optional<String> botToken();

    /** Chat id получателя. */
    Optional<String> chatId();

    /** Слать при переходе агента в blocked («нужен ввод»). */
    @WithDefault("true")
    boolean notifyBlocked();

    /** Слать при переходе агента в done («задача завершена»). */
    @WithDefault("true")
    boolean notifyDone();
}

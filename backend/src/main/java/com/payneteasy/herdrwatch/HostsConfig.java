package com.payneteasy.herdrwatch;

import com.payneteasy.herdrwatch.model.DataSource;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.util.List;
import java.util.Optional;

/**
 * Bootstrap-конфигурация: список herdr-серверов для мониторинга.
 * Задаётся в application.yaml под ключом "herdr-watch".
 * Позже правку через UI можно писать в отдельный state-файл поверх этого.
 */
@ConfigMapping(prefix = "herdr-watch")
public interface HostsConfig {

    List<Host> hosts();

    interface Host {
        /** Логический id, показывается в UI (напр. "dqa2", "m3-local"). */
        String id();

        /** SSH-таргет: имя из ~/.ssh/config или user@host. */
        String host();

        /** Путь к herdr на удалённой стороне. */
        @WithDefault("herdr")
        String herdrPath();

        /** Период опроса herdr на удалённой стороне, сек. */
        @WithDefault("2")
        int pollInterval();

        /** Пауза перед переподключением после обрыва, сек. */
        @WithDefault("5")
        int reconnectDelay();

        /** true — источник включён. */
        @WithDefault("true")
        boolean enabled();

        /** true — читать локальный herdr напрямую (без ssh), под текущим пользователем. */
        @WithDefault("false")
        boolean local();

        /** Необязательный override опций ssh (иначе берутся дефолтные). */
        Optional<String> sshExtraOpts();

        /** Способ получения данных: COMMAND (CLI, дефолт) или SOCKET (прямой unix-сокет herdr). */
        @WithDefault("COMMAND")
        DataSource dataSource();

        /** Путь к herdr.sock для socket-режима (иначе HERDR_SOCKET_PATH/дефолт). */
        Optional<String> socketPath();
    }
}

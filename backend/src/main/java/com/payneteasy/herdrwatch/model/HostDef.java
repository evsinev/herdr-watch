package com.payneteasy.herdrwatch.model;

import com.payneteasy.herdrwatch.HostsConfig;

/**
 * Плоское runtime-описание одного хоста. В отличие от {@link HostsConfig.Host}
 * (SmallRye ConfigMapping-прокси, экземпляр которого не создать вручную), это
 * обычный record — его можно собрать из bootstrap-конфига, из CRUD-запроса или
 * из state-файла и передать в {@link com.payneteasy.herdrwatch.source.SshSource}.
 *
 * Модель «одно ssh-соединение на хост» не меняется — меняется только носитель
 * параметров источника.
 */
public record HostDef(
        String id,
        String host,
        String herdrPath,
        int pollInterval,
        int reconnectDelay,
        boolean enabled,
        String sshExtraOpts,  // nullable — необязательный override опций ssh
        boolean local         // true — читать локальный herdr без ssh
) {
    /** Собрать из bootstrap-записи application.yaml. */
    public static HostDef from(HostsConfig.Host h) {
        return new HostDef(
                h.id(),
                h.host(),
                h.herdrPath(),
                h.pollInterval(),
                h.reconnectDelay(),
                h.enabled(),
                h.sshExtraOpts().orElse(null),
                h.local()
        );
    }
}

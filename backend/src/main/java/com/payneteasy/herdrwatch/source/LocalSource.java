package com.payneteasy.herdrwatch.source;

import com.payneteasy.herdrwatch.Registry;
import com.payneteasy.herdrwatch.model.HostDef;

import java.util.List;

/**
 * Локальный источник = тот же frame-цикл (см. {@link AbstractHerdrSource}), но
 * запускается локальной shell'ой без ssh. Так читается herdr ТЕКУЩЕГО
 * пользователя (а не чужая сессия за ssh).
 *
 * `bash -lc` — login-shell, чтобы подхватить PATH с herdr/jq. Посторонние строки
 * stdout (например, из профиля) безопасны: толерантный парсер кадров их игнорирует.
 */
public class LocalSource extends AbstractHerdrSource {

    public LocalSource(HostDef cfg, Registry registry) {
        super(cfg, registry);
    }

    @Override
    protected String kind() {
        return "local";
    }

    @Override
    protected String target() {
        return "local";
    }

    @Override
    protected List<String> processCommand(String frameCmd) {
        return List.of("bash", "-lc", frameCmd);
    }
}

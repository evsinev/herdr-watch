package com.payneteasy.herdrwatch.source;

import com.payneteasy.herdrwatch.Registry;
import com.payneteasy.herdrwatch.model.HostDef;

import java.util.ArrayList;
import java.util.List;

/**
 * Один источник = одно долгоживущее ssh-соединение с удалённым хостом.
 * Внутри ssh-сессии крутится frame-цикл (см. {@link AbstractHerdrSource});
 * здесь задаётся только сама ssh-команда запуска.
 */
public class SshSource extends AbstractHerdrSource {

    // Базовые опции, что и в bash-скрипте. Мультиплексирование соединений
    // (ControlMaster/ControlPath/ControlPersist) добавляем ТОЛЬКО не на Windows:
    // Windows OpenSSH его не реализует, а `~` в ControlPath не раскрывает — из-за чего
    // соединение может сразу отвалиться. См. sshBaseOpts().
    private static final String[] SSH_COMMON_OPTS = {
            "-o", "BatchMode=yes",
            "-o", "ServerAliveInterval=5",
            "-o", "ServerAliveCountMax=2",
            "-o", "ConnectTimeout=10",
    };

    private static final String[] SSH_MUX_OPTS = {
            "-o", "ControlMaster=auto",
            "-o", "ControlPath=~/.ssh/cm-herdr-%r@%h:%p",
            "-o", "ControlPersist=30s"
    };

    /**
     * Базовые ssh-опции для этой ОС (package-private: переиспользуется SocketDuplex.Remote).
     * На Windows опускаем мультиплексирование — Windows OpenSSH его не поддерживает.
     */
    static List<String> sshBaseOpts() {
        List<String> opts = new ArrayList<>(List.of(SSH_COMMON_OPTS));
        if (!IS_WINDOWS) opts.addAll(List.of(SSH_MUX_OPTS));
        return opts;
    }

    public SshSource(HostDef cfg, Registry registry) {
        super(cfg, registry);
    }

    @Override
    protected String kind() {
        return "ssh";
    }

    @Override
    protected String target() {
        return "ssh:" + cfg.host();
    }

    @Override
    protected List<String> processCommand(String frameCmd) {
        List<String> cmd = new ArrayList<>();
        cmd.add("ssh");
        cmd.addAll(sshBaseOpts());
        String extra = cfg.sshExtraOpts();
        if (extra != null && !extra.isBlank()) {
            for (String tok : extra.trim().split("\\s+")) if (!tok.isBlank()) cmd.add(tok);
        }
        cmd.add(cfg.host());
        // Прогоняем frame-скрипт через bash явно: он использует `read -t`, а логин-шелл на
        // удалённом хосте может быть dash (`read -t` там нет). Весь `bash -c '<скрипт>'`
        // передаём ОДНИМ ssh-аргументом — ssh отдаёт его remote-шеллу как есть, а тот
        // корректно разбирает одинарные кавычки и вызывает bash.
        cmd.add("bash -c " + singleQuote(frameCmd));
        return cmd;
    }

    /** Оборачивает строку в одинарные кавычки для POSIX-шелла, экранируя внутренние `'`. */
    static String singleQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }
}

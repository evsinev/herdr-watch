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

    // те же опции, что в bash-скрипте, плюс мультиплексирование соединений
    private static final String[] SSH_BASE_OPTS = {
            "-o", "BatchMode=yes",
            "-o", "ServerAliveInterval=5",
            "-o", "ServerAliveCountMax=2",
            "-o", "ConnectTimeout=10",
            "-o", "ControlMaster=auto",
            "-o", "ControlPath=~/.ssh/cm-herdr-%r@%h:%p",
            "-o", "ControlPersist=30s"
    };

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
        for (String o : SSH_BASE_OPTS) cmd.add(o);
        String extra = cfg.sshExtraOpts();
        if (extra != null && !extra.isBlank()) {
            for (String tok : extra.trim().split("\\s+")) if (!tok.isBlank()) cmd.add(tok);
        }
        cmd.add(cfg.host());
        cmd.add(frameCmd);
        return cmd;
    }
}

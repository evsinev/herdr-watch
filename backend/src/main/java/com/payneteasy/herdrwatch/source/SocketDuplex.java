package com.payneteasy.herdrwatch.source;

import com.payneteasy.herdrwatch.model.HostDef;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Двунаправленный поток NDJSON-строк к herdr socket API. Скрывает транспорт от протокол-логики
 * (она в {@link SocketSource} и от транспорта не зависит). Две реализации:
 * <ul>
 *   <li>{@link Local}  — прямое подключение к unix-сокету (Java 21 {@code SocketChannel}/UNIX);</li>
 *   <li>{@link Remote} — долгоживущий {@code ssh … exec socat - UNIX-CONNECT:<path>}: stdin/stdout
 *       ssh-процесса становится потоком удалённого сокета (одна ssh-сессия на хост, без jq на remote).</li>
 * </ul>
 */
public interface SocketDuplex extends Closeable {

    void writeLine(String json) throws IOException;

    /** Одна NDJSON-строка (без завершающего перевода строки) либо {@code null} при EOF. */
    String readLine() throws IOException;

    /** Путь к сокету: per-host override → {@code HERDR_SOCKET_PATH} (только local) → дефолт herdr. */
    static String resolveSocketPath(HostDef cfg) {
        String p = cfg.socketPath();
        if (p != null && !p.isBlank()) return p.trim();
        if (cfg.local()) {
            String env = System.getenv("HERDR_SOCKET_PATH");
            if (env != null && !env.isBlank()) return env.trim();
        }
        return "~/.config/herdr/herdr.sock";
    }

    /** Открыть подходящий транспорт для хоста (local unix-socket / remote ssh+socat). */
    static SocketDuplex open(HostDef cfg) throws IOException {
        String path = resolveSocketPath(cfg);
        return cfg.local() ? new Local(path) : new Remote(cfg, path);
    }

    /** local: прямой unix-сокет через SocketChannel. */
    final class Local implements SocketDuplex {
        private final SocketChannel ch;
        private final BufferedReader r;
        private final BufferedWriter w;

        Local(String path) throws IOException {
            String expanded = path.startsWith("~/")
                    ? System.getProperty("user.home") + path.substring(1)
                    : path;
            ch = SocketChannel.open(StandardProtocolFamily.UNIX);
            ch.connect(UnixDomainSocketAddress.of(Path.of(expanded)));
            r = new BufferedReader(new InputStreamReader(Channels.newInputStream(ch), StandardCharsets.UTF_8));
            w = new BufferedWriter(new OutputStreamWriter(Channels.newOutputStream(ch), StandardCharsets.UTF_8));
        }

        @Override public void writeLine(String json) throws IOException { w.write(json); w.write('\n'); w.flush(); }
        @Override public String readLine() throws IOException { return r.readLine(); }
        @Override public void close() throws IOException { ch.close(); }
    }

    /** remote: ssh + socat; stdin/stdout процесса = поток удалённого unix-сокета. */
    final class Remote implements SocketDuplex {
        private final Process proc;
        private final BufferedReader r;
        private final BufferedWriter w;

        Remote(HostDef cfg, String path) throws IOException {
            List<String> cmd = new ArrayList<>();
            cmd.add("ssh");
            for (String o : SshSource.SSH_BASE_OPTS) cmd.add(o);
            String extra = cfg.sshExtraOpts();
            if (extra != null && !extra.isBlank()) {
                for (String tok : extra.trim().split("\\s+")) if (!tok.isBlank()) cmd.add(tok);
            }
            cmd.add(cfg.host());
            // remote-shell раскроет ~; socat пробрасывает unix-сокет herdr на той стороне в stdin/stdout
            cmd.add("exec socat - UNIX-CONNECT:" + path);
            proc = new ProcessBuilder(cmd).redirectErrorStream(false).start();
            r = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8));
            w = new BufferedWriter(new OutputStreamWriter(proc.getOutputStream(), StandardCharsets.UTF_8));
        }

        @Override public void writeLine(String json) throws IOException { w.write(json); w.write('\n'); w.flush(); }
        @Override public String readLine() throws IOException { return r.readLine(); }
        @Override public void close() { proc.destroyForcibly(); }
    }
}

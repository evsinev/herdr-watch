package com.payneteasy.herdrwatch.source;

import com.payneteasy.herdrwatch.model.DataSource;
import com.payneteasy.herdrwatch.model.HostDef;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Проверяет local-транспорт {@link SocketDuplex} (Java 21 unix-socket + NDJSON-framing)
 * против фейкового unix-сокет-сервера: одна строка-запрос → одна строка-ответ.
 */
class SocketDuplexTest {

    @Test
    void localUnixSocketRoundTrip() throws Exception {
        Path dir = Files.createTempDirectory("hw-sock");
        Path sock = dir.resolve("t.sock");

        ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        server.bind(UnixDomainSocketAddress.of(sock));

        AtomicReference<String> received = new AtomicReference<>();
        Thread acceptor = new Thread(() -> {
            try (SocketChannel ch = server.accept()) {
                BufferedReader r = new BufferedReader(
                        new InputStreamReader(Channels.newInputStream(ch), StandardCharsets.UTF_8));
                BufferedWriter w = new BufferedWriter(
                        new OutputStreamWriter(Channels.newOutputStream(ch), StandardCharsets.UTF_8));
                String line = r.readLine();
                received.set(line);
                w.write("{\"id\":\"echo\",\"result\":{\"type\":\"pong\"}}\n");
                w.flush();
                // подождём, пока клиент прочитает, затем закрытие по try-with-resources
                Thread.sleep(300);
            } catch (Exception ignored) {
            }
        });
        acceptor.setDaemon(true);
        acceptor.start();

        HostDef cfg = new HostDef("t", "local", "herdr", 2, 5, true, null, true,
                DataSource.SOCKET, sock.toString());

        try (SocketDuplex d = SocketDuplex.open(cfg)) {
            d.writeLine("{\"id\":\"echo\",\"method\":\"ping\",\"params\":{}}");
            String resp = d.readLine();
            assertNotNull(resp, "должны получить ответ-строку");
            assertTrue(resp.contains("\"result\""), "ответ содержит result: " + resp);
            assertTrue(resp.contains("pong"));
        }

        acceptor.join(1000);
        assertNotNull(received.get(), "сервер должен был получить запрос");
        assertTrue(received.get().contains("\"method\":\"ping\""));
        assertEquals(-1, received.get().indexOf('\n'), "readLine отдаёт строку без перевода строки");

        server.close();
        Files.deleteIfExists(sock);
        Files.deleteIfExists(dir);
    }

    @Test
    void resolveSocketPathPrefersConfigThenDefault() {
        HostDef withPath = new HostDef("a", "local", "herdr", 2, 5, true, null, true,
                DataSource.SOCKET, "/tmp/custom.sock");
        assertEquals("/tmp/custom.sock", SocketDuplex.resolveSocketPath(withPath));

        HostDef noPath = new HostDef("b", "local", "herdr", 2, 5, true, null, true,
                DataSource.SOCKET, null);
        // при незаданном пути и без env — дефолт herdr (env в CI обычно не выставлен)
        String resolved = SocketDuplex.resolveSocketPath(noPath);
        assertTrue(resolved.endsWith(".config/herdr/herdr.sock") || resolved.equals(System.getenv("HERDR_SOCKET_PATH")),
                "ожидали дефолт или HERDR_SOCKET_PATH, получили: " + resolved);
    }
}

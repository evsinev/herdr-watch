package com.payneteasy.herdrwatch.usage.pull;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Креденшл из macOS Keychain, service {@code Claude Code-credentials}.
 *
 * <p><b>Почему здесь перебор, а не один вызов.</b> Под этим service может лежать
 * НЕСКОЛЬКО generic-password записей, отличающихся только аккаунтом, и часть из них —
 * протухшие. {@code security find-generic-password -s <svc> -w} без {@code -a} отдаёт
 * первую попавшуюся: на референсной машине это стабильно мёртвая запись от 19 июля.
 * Именно на этом когда-то закрыли всю pull-ветку, решив, что креденшла нет. Поэтому
 * собираем всех кандидатов и выбираем по существу (см. {@link CredentialSource#best}),
 * а не по порядку в хранилище.
 *
 * <p>Через {@code /usr/bin/security}, а не Security framework: так ACL принадлежит
 * бинарю Apple и пользовательское «always allow» переживает пересборки приложения.
 * Только чтение — ни одна команда здесь не пишет и не удаляет.
 */
public class KeychainCredentialSource implements CredentialSource {

    private static final Logger log = LoggerFactory.getLogger(KeychainCredentialSource.class);
    private static final String SECURITY = "/usr/bin/security";
    private static final long TIMEOUT_SECONDS = 10;

    private final String service;

    public KeychainCredentialSource(String service) {
        this.service = service;
    }

    public static boolean isSupported() {
        return System.getProperty("os.name", "").toLowerCase().contains("mac");
    }

    @Override
    public List<ClaudeCredential> candidates() throws CredentialAccessException {
        if (!isSupported()) return List.of();

        List<ClaudeCredential> out = new ArrayList<>();
        for (String account : accounts()) {
            String raw = read(account);
            ClaudeCredential c = CredentialJson.parse(raw);
            if (c != null) out.add(c);
        }
        if (out.isEmpty()) {
            // Аккаунтов не нашли (или dump недоступен) — пробуем поиск без -a как запасной путь.
            ClaudeCredential c = CredentialJson.parse(read(null));
            if (c != null) out.add(c);
        }
        return out;
    }

    /**
     * Аккаунты всех записей нашего service. {@code dump-keychain} без {@code -d} отдаёт
     * только метаданные — пароли не читаются и диалог не показывается.
     */
    Set<String> accounts() throws CredentialAccessException {
        Set<String> accounts = new LinkedHashSet<>();
        String dump = run(new String[] {SECURITY, "dump-keychain"}, true);
        if (dump == null) return accounts;

        String pendingAccount = null;
        for (String line : dump.split("\n")) {
            String t = line.trim();
            if (t.startsWith("\"acct\"<blob>=")) {
                pendingAccount = unquote(t.substring(t.indexOf('=') + 1));
            } else if (t.startsWith("\"svce\"<blob>=")) {
                String svce = unquote(t.substring(t.indexOf('=') + 1));
                if (service.equals(svce) && pendingAccount != null) accounts.add(pendingAccount);
            }
        }
        return accounts;
    }

    /** Содержимое одной записи; null — не нашлось. */
    private String read(String account) throws CredentialAccessException {
        List<String> cmd = new ArrayList<>(List.of(SECURITY, "find-generic-password", "-s", service));
        if (account != null) {
            cmd.add("-a");
            cmd.add(account);
        }
        cmd.add("-w");
        return run(cmd.toArray(new String[0]), false);
    }

    /**
     * Запуск {@code security}. Возвращает stdout+stderr (пароль {@code -w} печатается в
     * stderr, а не в stdout — легко потерять, если читать только stdout).
     */
    private String run(String[] cmd, boolean tolerateFailure) throws CredentialAccessException {
        Process p = null;
        try {
            p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new CredentialAccessException("keychain lookup timed out");
            }
            if (p.exitValue() != 0) {
                if (tolerateFailure) return null;
                // 44/45 — «не найдено»; всё прочее трактуем как отказ в доступе.
                if (p.exitValue() == 44 || p.exitValue() == 45) return null;
                throw new CredentialAccessException("security exited " + p.exitValue());
            }
            return out;
        } catch (IOException e) {
            throw new CredentialAccessException("cannot run security: " + e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CredentialAccessException("interrupted");
        } finally {
            if (p != null && p.isAlive()) p.destroyForcibly();
        }
    }

    private static String unquote(String s) {
        String t = s.trim();
        if (t.startsWith("\"") && t.endsWith("\"") && t.length() >= 2) {
            return t.substring(1, t.length() - 1);
        }
        return t;
    }

    @Override
    public String describe() {
        return "macOS keychain service \"" + service + "\"";
    }
}

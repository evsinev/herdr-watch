package com.payneteasy.herdrwatch.usage.pull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Креденшл из файла — путь для headless-хостов (Linux, контейнер, native-бинарь),
 * где Keychain'а нет. Формат тот же, что в Keychain.
 */
public class FileCredentialSource implements CredentialSource {

    public static final String DEFAULT_PATH = "~/.claude/.credentials.json";

    private final Path file;

    public FileCredentialSource(String rawPath) {
        this.file = resolve(rawPath == null || rawPath.isBlank() ? DEFAULT_PATH : rawPath);
    }

    @Override
    public List<ClaudeCredential> candidates() throws CredentialAccessException {
        if (!Files.isReadable(file)) {
            if (Files.exists(file)) throw new CredentialAccessException("not readable: " + file);
            return List.of();
        }
        try {
            ClaudeCredential c = CredentialJson.parse(Files.readString(file, StandardCharsets.UTF_8));
            return c == null ? List.of() : List.of(c);
        } catch (IOException e) {
            throw new CredentialAccessException("unreadable " + file + ": " + e);
        }
    }

    @Override
    public String describe() {
        return "file " + file;
    }

    static Path resolve(String raw) {
        String p = raw.trim();
        if (p.startsWith("~")) p = System.getProperty("user.home") + p.substring(1);
        return Path.of(p);
    }
}

package com.payneteasy.herdrwatch.usage.pull;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Разбор формата, в котором Claude Code хранит креденшл — одинакового и в Keychain,
 * и в {@code ~/.claude/.credentials.json}:
 *
 * <pre>{ "claudeAiOauth": { "accessToken": …, "expiresAt": &lt;МИЛЛИсекунды&gt;, "scopes": [ … ] } }</pre>
 */
final class CredentialJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CredentialJson() {}

    /** Разбор одной записи. Возвращает null, если это не наш формат. */
    static ClaudeCredential parse(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            JsonNode root = MAPPER.readTree(raw);
            JsonNode oauth = root == null ? null : root.get("claudeAiOauth");
            if (oauth == null || !oauth.isObject()) return null;

            JsonNode token = oauth.get("accessToken");
            if (token == null || !token.isTextual() || token.textValue().isBlank()) return null;

            // expiresAt — МИЛЛИсекунды. Прочитать как секунды = объявить живой токен древним.
            JsonNode expires = oauth.get("expiresAt");
            Long expiresAtMillis = (expires != null && expires.canConvertToLong())
                    ? expires.longValue() : null;

            List<String> scopes = new ArrayList<>();
            JsonNode scopeNode = oauth.get("scopes");
            if (scopeNode != null && scopeNode.isArray()) {
                scopeNode.forEach(n -> { if (n.isTextual()) scopes.add(n.textValue()); });
            }
            return ClaudeCredential.of(token.textValue(), expiresAtMillis, scopes);
        } catch (IOException e) {
            return null;
        }
    }
}

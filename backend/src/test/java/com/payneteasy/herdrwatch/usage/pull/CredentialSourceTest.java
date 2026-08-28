package com.payneteasy.herdrwatch.usage.pull;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Выбор креденшла. Здесь когда-то ошиблись так, что закрыли целую ветку дизайна:
 * под одним service лежало два элемента, поиск отдавал мёртвый. Тесты держат
 * именно этот случай.
 */
class CredentialSourceTest {

    private static final Instant NOW = Instant.parse("2026-08-28T00:00:00Z");
    private static final List<String> FULL_SCOPES =
            List.of("user:file_upload", "user:inference", "user:mcp_servers",
                    "user:profile", "user:sessions:claude_code");

    private static ClaudeCredential cred(String token, String expiresAt, List<String> scopes) {
        Long millis = expiresAt == null ? null : Instant.parse(expiresAt).toEpochMilli();
        return ClaudeCredential.of(token, millis, scopes);
    }

    // --- выбор по существу, а не по порядку ---

    @Test
    void picksTheLiveItemWhenAnExpiredOneComesFirst() {
        // Форма референсной машины: acct="no" (мёртвый, отдаётся первым) и acct=<user> (живой).
        List<ClaudeCredential> candidates = List.of(
                cred("dead", "2026-07-19T18:19:00Z", FULL_SCOPES),
                cred("live", "2026-08-28T11:19:00Z", FULL_SCOPES));

        CredentialLookup r = CredentialSource.best(candidates, NOW);

        assertTrue(r.isFound());
        assertEquals("live", r.credential().accessToken(),
                "порядок в хранилище не должен решать — решает срок и скоуп");
    }

    @Test
    void picksTheLiveItemRegardlessOfOrder() {
        List<ClaudeCredential> reversed = List.of(
                cred("live", "2026-08-28T11:19:00Z", FULL_SCOPES),
                cred("dead", "2026-07-19T18:19:00Z", FULL_SCOPES));
        assertEquals("live", CredentialSource.best(reversed, NOW).credential().accessToken());
    }

    @Test
    void severalLiveCandidatesPickTheLatestExpiry() {
        List<ClaudeCredential> candidates = List.of(
                cred("soon", "2026-08-28T01:00:00Z", FULL_SCOPES),
                cred("later", "2026-08-28T11:19:00Z", FULL_SCOPES));
        assertEquals("later", CredentialSource.best(candidates, NOW).credential().accessToken());
    }

    @Test
    void allExpiredIsNotConfiguredNotAStaleToken() {
        List<ClaudeCredential> candidates = List.of(
                cred("dead1", "2026-07-19T18:19:00Z", FULL_SCOPES),
                cred("dead2", "2026-07-20T07:36:00Z", FULL_SCOPES));

        CredentialLookup r = CredentialSource.best(candidates, NOW);

        assertEquals(CredentialLookup.Status.NOT_CONFIGURED, r.status());
        assertNull(r.credential(), "мёртвый токен отдавать нельзя");
    }

    @Test
    void liveButWithoutProfileScopeIsNotAuthorized() {
        // Ровно то, что даёт `claude setup-token`: inference-only.
        List<ClaudeCredential> candidates = List.of(
                cred("inference-only", "2026-08-28T11:19:00Z", List.of("user:inference")));

        CredentialLookup r = CredentialSource.best(candidates, NOW);

        assertEquals(CredentialLookup.Status.NOT_AUTHORIZED, r.status());
        assertNull(r.credential());
    }

    @Test
    void nothingAtAllIsNotConfigured() {
        assertEquals(CredentialLookup.Status.NOT_CONFIGURED,
                CredentialSource.best(List.of(), NOW).status());
    }

    @Test
    void credentialWithoutExpiryIsTrustedUntilTheApiSaysOtherwise() {
        List<ClaudeCredential> candidates = List.of(cred("no-expiry", null, FULL_SCOPES));
        assertTrue(CredentialSource.best(candidates, NOW).isFound());
    }

    // --- expiresAt в МИЛЛИсекундах ---

    @Test
    void expiresAtIsReadAsMillisecondsNotSeconds() {
        long millis = Instant.parse("2026-08-28T11:19:00Z").toEpochMilli();
        ClaudeCredential c = ClaudeCredential.of("t", millis, FULL_SCOPES);

        assertEquals(Instant.parse("2026-08-28T11:19:00Z"), c.expiresAt());
        assertFalse(c.isExpired(NOW), "прочитанный как секунды, этот токен выглядел бы древним");
    }

    // --- файловый источник ---

    @Test
    void fileSourceReadsTheClaudeCodeShape(@TempDir Path dir) throws Exception {
        Path f = dir.resolve(".credentials.json");
        Files.writeString(f, """
                { "claudeAiOauth": { "accessToken": "tok", "refreshToken": "r",
                  "expiresAt": %d, "scopes": ["user:profile", "user:inference"] } }
                """.formatted(Instant.parse("2026-08-28T11:19:00Z").toEpochMilli()));

        List<ClaudeCredential> got = new FileCredentialSource(f.toString()).candidates();

        assertEquals(1, got.size());
        assertEquals("tok", got.get(0).accessToken());
        assertTrue(got.get(0).hasProfileScope());
    }

    @Test
    void missingFileIsEmptyNotAnError(@TempDir Path dir) throws Exception {
        assertTrue(new FileCredentialSource(dir.resolve("absent.json").toString())
                .candidates().isEmpty());
    }

    @Test
    void garbageFileIsIgnored(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("bad.json");
        Files.writeString(f, "{ not json");
        assertTrue(new FileCredentialSource(f.toString()).candidates().isEmpty());
    }

    @Test
    void unreadableFileIsAnAccessFailureNotSilence(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("locked.json");
        Files.writeString(f, "{}");
        assertTrue(f.toFile().setReadable(false), "среда не позволяет снять чтение");
        try {
            assertThrows(CredentialAccessException.class,
                    () -> new FileCredentialSource(f.toString()).candidates());
        } finally {
            f.toFile().setReadable(true);
        }
    }

    // --- только чтение ---

    @Test
    void lookupNeverModifiesTheStore(@TempDir Path dir) throws Exception {
        Path f = dir.resolve(".credentials.json");
        String content = """
                { "claudeAiOauth": { "accessToken": "tok", "expiresAt": %d,
                  "scopes": ["user:profile"] } }
                """.formatted(Instant.parse("2026-07-01T00:00:00Z").toEpochMilli());
        Files.writeString(f, content);
        byte[] before = Files.readAllBytes(f);
        long mtimeBefore = Files.getLastModifiedTime(f).toMillis();

        // Токен просрочен — соблазн «обновить» максимальный; обновлять нельзя.
        new ChainedCredentialSource(List.of(new FileCredentialSource(f.toString()))).lookup(NOW);

        assertArrayEqualsBytes(before, Files.readAllBytes(f));
        assertEquals(mtimeBefore, Files.getLastModifiedTime(f).toMillis(), "mtime не должен двигаться");
    }

    @Test
    void keychainSourceIssuesNoMutatingCommand() throws Exception {
        // Страховка от правки «заодно почистим протухшее»: в исходнике не должно быть
        // ни одной изменяющей подкоманды security.
        String src = Files.readString(
                Path.of("src/main/java/com/payneteasy/herdrwatch/usage/pull/KeychainCredentialSource.java"),
                StandardCharsets.UTF_8);
        for (String forbidden : List.of("delete-generic-password", "add-generic-password",
                                        "set-generic-password", "unlock-keychain")) {
            assertFalse(src.contains(forbidden), "источник обязан быть read-only, найдено: " + forbidden);
        }
    }

    // --- токен не должен утечь в лог ---

    @Test
    void toStringNeverCarriesTheToken() {
        // record печатает все поля по умолчанию — здесь toString переопределён намеренно.
        ClaudeCredential c = cred("sk-ant-oat01-SUPER-SECRET", "2026-08-28T11:19:00Z", FULL_SCOPES);

        assertFalse(c.toString().contains("SUPER-SECRET"), "токен в toString(): " + c);
        assertTrue(c.toString().contains("***"));
    }

    @Test
    void lookupSummaryNeverCarriesTheToken() {
        ClaudeCredential c = cred("sk-ant-oat01-SUPER-SECRET", "2026-08-28T11:19:00Z", FULL_SCOPES);
        CredentialLookup found = CredentialLookup.found(c);

        assertFalse(found.summary().contains("SUPER-SECRET"));
        assertFalse(CredentialLookup.notConfigured("nothing").summary().contains("SUPER-SECRET"));
        assertNotNull(found.credential());
    }

    private static void assertArrayEqualsBytes(byte[] a, byte[] b) {
        assertEquals(new String(a, StandardCharsets.UTF_8), new String(b, StandardCharsets.UTF_8));
    }
}

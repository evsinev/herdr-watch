package com.payneteasy.herdrwatch.usage.pull;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Версия установленного Claude Code для User-Agent. Отпечаток должен совпадать с
 * реальным клиентом: по наблюдениям устаревшая версия рискует попасть в более
 * строгий бакет, поэтому определяем, а не хардкодим.
 */
public final class ClaudeCliVersion {

    /** Запасное значение, если определить не удалось. */
    public static final String FALLBACK = "2.1.250";

    private static final Pattern SEMVER = Pattern.compile("(\\d+\\.\\d+\\.\\d+)");

    private ClaudeCliVersion() {}

    public static String userAgent(String version) {
        return "claude-cli/" + version + " (external, cli)";
    }

    /** {@code claude --version} → «2.1.250 (Claude Code)». Ошибки не пробрасываем. */
    public static String detect() {
        Process p = null;
        try {
            p = new ProcessBuilder("claude", "--version").redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes());
            if (!p.waitFor(10, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return FALLBACK;
            }
            Matcher m = SEMVER.matcher(out);
            return m.find() ? m.group(1) : FALLBACK;
        } catch (IOException e) {
            return FALLBACK;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return FALLBACK;
        } finally {
            if (p != null && p.isAlive()) p.destroyForcibly();
        }
    }
}

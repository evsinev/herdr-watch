package com.payneteasy.herdrwatch.usage.pull;

import com.payneteasy.herdrwatch.Registry;
import com.payneteasy.herdrwatch.usage.ClaudeUsage;
import com.payneteasy.herdrwatch.usage.ClaudeUsageConfig;
import com.payneteasy.herdrwatch.usage.UsageSource;

import io.quarkus.scheduler.Scheduled;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Pull-источник: опрашивает аккаунт-API и публикует снапшот в {@link Registry}.
 *
 * <p>Планировщик тикает часто и дёшево, а решает, пора ли стучаться, {@link PollPolicy}:
 * задержка между попытками переменная (серверный {@code Retry-After} может отбросить
 * нас на десятки минут), и зашить её в {@code @Scheduled} нельзя.
 *
 * <p>Ничего из происходящего здесь не должно касаться сбора кадров хостов — все
 * исключения остаются внутри.
 */
@ApplicationScoped
public class ClaudeUsagePullReader {

    private static final Logger log = LoggerFactory.getLogger(ClaudeUsagePullReader.class);

    @Inject ClaudeUsageConfig config;
    @Inject Registry registry;

    private boolean enabled;
    private String disabledReason;
    private PollPolicy policy;
    private PollPolicy.State state = PollPolicy.State.fresh();
    private ChainedCredentialSource credentials;
    private ClaudeUsageApiClient client;

    /** Раньше этого момента не стучимся. */
    private Instant nextAttemptAt = Instant.EPOCH;
    /** Последний удачно полученный снапшот — его же отдаём помеченным устаревшим. */
    private ClaudeUsage lastGood;

    @PostConstruct
    void init() {
        ClaudeUsageConfig.Pull pull = config.pull();

        if (!config.source().usesPull()) {
            disable("source=" + config.source().name().toLowerCase() + " — pull не выбран");
            return;
        }
        // Fail closed: без осознанного согласия на отпечаток клиента не стартуем вовсе.
        // Слать свой User-Agent «чтобы хоть как-то» нельзя — это гарантированный
        // жёсткий бакет и отказ, который оператору пришлось бы отлаживать (дизайн D3).
        if (!pull.impersonateClaudeCli()) {
            disable("herdr-watch.claude-usage.pull.impersonate-claude-cli=false — "
                    + "pull-источник не запущен: эндпоинт требует User-Agent Claude Code, "
                    + "и отправка чужого отпечатка должна быть осознанным решением");
            return;
        }

        try {
            policy = new PollPolicy(
                    duration(pull.pollInterval(), Duration.ofMinutes(5)),
                    duration(pull.backoffFloor(), Duration.ofMinutes(1)),
                    duration(pull.backoffCap(), Duration.ofHours(2)),
                    duration(pull.retryMargin(), Duration.ofSeconds(30)));
        } catch (IllegalArgumentException e) {
            disable("неверная политика опроса: " + e.getMessage());
            return;
        }

        List<CredentialSource> sources = new ArrayList<>();
        if (KeychainCredentialSource.isSupported()) {
            sources.add(new KeychainCredentialSource(pull.keychainService()));
        }
        sources.add(new FileCredentialSource(pull.credentialsFile()));
        credentials = new ChainedCredentialSource(sources);

        String version = pull.claudeCliVersion().filter(v -> !v.isBlank())
                .orElseGet(ClaudeCliVersion::detect);
        client = new ClaudeUsageApiClient(URI.create(pull.endpoint()),
                ClaudeCliVersion.userAgent(version));

        enabled = true;
        log.info("claude usage pull: enabled, endpoint {}, poll {} (client fingerprint: claude-cli/{})",
                pull.endpoint(), pull.pollInterval(), version);
    }

    private void disable(String reason) {
        enabled = false;
        disabledReason = reason;
        if (config.source().usesPull()) log.warn("claude usage pull: {}", reason);
        else log.debug("claude usage pull: {}", reason);
    }

    @Scheduled(every = "30s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void poll() {
        if (!enabled) return;
        try {
            tick(Instant.now());
        } catch (Exception e) {
            // Проблема этого источника не имеет права трогать цикл опроса хостов.
            log.warn("claude usage pull: tick failed: {}", e.toString());
        }
    }

    /** Один цикл. Не бросает. */
    void tick(Instant now) {
        if (now.isBefore(nextAttemptAt)) return;

        CredentialLookup lookup = credentials.lookup(now);
        if (!lookup.isFound()) {
            // Креденшла нет — запроса не делаем вовсе, чтобы не жечь бакет впустую.
            state = state.after(PullOutcome.Result.of(PullOutcome.NO_CREDENTIAL, lookup.summary()));
            publishFailure(lookup.summary());
            schedule(now, null);
            return;
        }

        ClaudeUsageApiClient.Fetched fetched = client.fetch(lookup.credential(), now);
        state = state.after(fetched.result());

        if (fetched.result().outcome() == PullOutcome.OK) {
            lastGood = fetched.usage();
            registry.updateClaudeUsage(fetched.usage());
        } else {
            log.debug("claude usage pull: {} ({})",
                    fetched.result().outcome(), fetched.result().detail());
            publishFailure(fetched.result().outcome() + ": " + fetched.result().detail());
        }
        schedule(now, fetched.result().retryAfter());
    }

    /** Прошлый снапшот, помеченный устаревшим; частичных значений не публикуем. */
    private void publishFailure(String reason) {
        if (lastGood != null) {
            registry.updateClaudeUsage(lastGood.stale(reason));
        } else {
            registry.updateClaudeUsage(new ClaudeUsage(
                    ClaudeUsage.State.NOT_CONFIGURED, UsageSource.ACCOUNT_API,
                    null, reason, new ClaudeUsage.Windows(null, null), List.of()));
        }
    }

    private void schedule(Instant now, Duration serverRetryAfter) {
        nextAttemptAt = now.plus(policy.nextDelay(state, serverRetryAfter));
    }

    // --- для тестов ---

    boolean isEnabled() {
        return enabled;
    }

    String disabledReason() {
        return disabledReason;
    }

    static Duration duration(String raw, Duration fallback) {
        try {
            String s = raw.trim();
            if (s.isEmpty()) return fallback;
            if (!s.startsWith("P") && !s.startsWith("p")) s = "PT" + s;
            return Duration.parse(s);
        } catch (Exception e) {
            return fallback;
        }
    }
}

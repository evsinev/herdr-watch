package com.payneteasy.herdrwatch.usage.pull;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Keychain → файл → override. Собираем кандидатов из ВСЕХ источников и выбираем
 * лучшего одним решением: источник, отдавший протухший токен, не должен закрывать
 * собой источник с живым.
 */
public class ChainedCredentialSource {

    private static final Logger log = LoggerFactory.getLogger(ChainedCredentialSource.class);

    private final List<CredentialSource> sources;

    public ChainedCredentialSource(List<CredentialSource> sources) {
        this.sources = List.copyOf(sources);
    }

    public CredentialLookup lookup(Instant now) {
        List<ClaudeCredential> all = new ArrayList<>();
        boolean denied = false;
        String deniedDetail = null;

        for (CredentialSource s : sources) {
            try {
                all.addAll(s.candidates());
            } catch (CredentialAccessException e) {
                denied = true;
                deniedDetail = s.describe() + ": " + e.getMessage();
                log.debug("claude usage pull: {}", deniedDetail);
            }
        }

        CredentialLookup result = CredentialSource.best(all, now);
        // Отказ в доступе важнее «ничего не нашли»: это не «не настроено», это «не пустили».
        if (!result.isFound()
                && result.status() == CredentialLookup.Status.NOT_CONFIGURED
                && denied) {
            return CredentialLookup.accessDenied(deniedDetail);
        }
        return result;
    }
}

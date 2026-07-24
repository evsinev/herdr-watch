package com.payneteasy.herdrwatch;

import com.payneteasy.herdrwatch.model.HostDef;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Слой персистентности списка хостов: записываемый state-файл поверх read-only
 * bootstrap-конфига из application.yaml.
 *
 * Модель — delta: bootstrap считается базой, а в state-файле хранятся только
 * пользовательские правки/добавления (overrides) и тумбстоны удалённых
 * bootstrap-хостов (removed). Итоговый список = (bootstrap ∪ overrides) − removed,
 * при совпадении id override побеждает («state file wins»). В application.yaml
 * не пишем никогда.
 *
 * Формат файла (~/.config/herdr-watch/hosts.json по умолчанию):
 * <pre>{ "hosts": [HostDef...], "removed": ["id1", ...] }</pre>
 */
@ApplicationScoped
public class HostStore {

    private static final Logger log = LoggerFactory.getLogger(HostStore.class);

    /** JSON-снимок state-файла. */
    public record StateFile(List<HostDef> hosts, List<String> removed) {}

    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Inject
    HostsConfig config;

    @ConfigProperty(name = "herdr-watch.state-file", defaultValue = "~/.config/herdr-watch/hosts.json")
    String stateFilePath;

    private Path file;

    // база из application.yaml (неизменна в рамках запуска)
    private final Map<String, HostDef> bootstrap = new LinkedHashMap<>();
    // правки/добавления пользователя (персистятся)
    private final Map<String, HostDef> overrides = new LinkedHashMap<>();
    // тумбстоны удалённых bootstrap-хостов (персистятся)
    private final Set<String> removed = new LinkedHashSet<>();

    @PostConstruct
    void init() {
        file = resolvePath(stateFilePath);
        for (HostsConfig.Host h : config.hosts()) {
            bootstrap.put(h.id(), HostDef.from(h));
        }
        load();
        log.info("host store: {} bootstrap, {} overrides, {} tombstones -> {} active (state file {})",
                bootstrap.size(), overrides.size(), removed.size(), merged().size(), file);
    }

    /** Итоговый список хостов (bootstrap ∪ overrides − removed). */
    public synchronized List<HostDef> all() {
        return new ArrayList<>(merged().values());
    }

    public synchronized Optional<HostDef> get(String id) {
        return Optional.ofNullable(merged().get(id));
    }

    public synchronized boolean exists(String id) {
        return merged().containsKey(id);
    }

    /** Добавить хост. id должен быть уникальным — проверяется в ресурсе. */
    public synchronized HostDef add(HostDef d) {
        overrides.put(d.id(), d);
        removed.remove(d.id());   // повторное добавление снимает тумбстон
        persist();
        return d;
    }

    /** Обновить существующий хост (id неизменяем). */
    public synchronized HostDef update(String id, HostDef d) {
        overrides.put(d.id(), d);
        persist();
        return d;
    }

    /** Удалить хост: снять override и, если это bootstrap-хост, поставить тумбстон. */
    public synchronized void remove(String id) {
        overrides.remove(id);
        if (bootstrap.containsKey(id)) {
            removed.add(id);      // иначе воскреснет из application.yaml на рестарте
        }
        persist();
    }

    // --- внутреннее ---

    private Map<String, HostDef> merged() {
        Map<String, HostDef> m = new LinkedHashMap<>(bootstrap);
        m.putAll(overrides);            // override побеждает / добавляет
        for (String id : removed) m.remove(id);
        return m;
    }

    private void load() {
        if (!Files.exists(file)) return;
        try {
            StateFile sf = mapper.readValue(file.toFile(), StateFile.class);
            if (sf.hosts() != null) {
                for (HostDef d : sf.hosts()) overrides.put(d.id(), d);
            }
            if (sf.removed() != null) {
                removed.addAll(sf.removed());
            }
        } catch (IOException e) {
            log.error("failed to read state file {} — starting from bootstrap only: {}", file, e.toString());
        }
    }

    /** Атомарная запись: во временный файл + rename. */
    private void persist() {
        try {
            Path dir = file.getParent();
            if (dir != null) Files.createDirectories(dir);
            StateFile sf = new StateFile(new ArrayList<>(overrides.values()), new ArrayList<>(removed));
            Path tmp = Files.createTempFile(dir, "hosts", ".json.tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), sf);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            log.error("failed to write state file {}: {}", file, e.toString());
            throw new RuntimeException("Could not persist host configuration", e);
        }
    }

    private static Path resolvePath(String raw) {
        String p = raw.trim();
        if (p.startsWith("~")) {
            p = System.getProperty("user.home") + p.substring(1);
        }
        return Path.of(p);
    }
}

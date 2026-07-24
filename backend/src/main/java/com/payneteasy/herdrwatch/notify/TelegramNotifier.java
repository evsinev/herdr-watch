package com.payneteasy.herdrwatch.notify;

import com.payneteasy.herdrwatch.FrameApplied;
import com.payneteasy.herdrwatch.TelegramConfig;
import com.payneteasy.herdrwatch.model.Model.AgentInfo;
import com.payneteasy.herdrwatch.model.Model.Health;
import com.payneteasy.herdrwatch.model.Model.HostState;
import com.payneteasy.herdrwatch.model.Model.WorkspaceInfo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.quarkus.runtime.StartupEvent;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Telegram-оповещения на ПЕРЕХОДЕ статуса агента: blocked («нужен ввод») и done
 * («задача завершена»). Слушает CDI-событие {@link FrameApplied}, которое Registry
 * шлёт после каждого CONNECTED-кадра (надёжнее, чем ещё один подписчик Mutiny-шины).
 *
 * Диффим prev → now. Baseline (без оповещений): если prev был UNREACHABLE —
 * это первый кадр хоста или реконнект, чтобы не спамить накопленными переходами.
 */
@ApplicationScoped
public class TelegramNotifier {

    private static final Logger log = LoggerFactory.getLogger(TelegramNotifier.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject TelegramConfig cfg;

    private HttpClient http;   // != null только когда оповещения включены
    private String botToken;
    private String chatId;

    void onStart(@Observes StartupEvent ev) {
        String token = cfg.botToken().map(String::trim).orElse("");
        String chat = cfg.chatId().map(String::trim).orElse("");
        if (!cfg.enabled() || token.isEmpty() || chat.isEmpty()) {
            log.info("telegram notifications disabled (enabled={}, token={}, chatId={})",
                    cfg.enabled(),
                    token.isEmpty() ? "missing" : "set",
                    chat.isEmpty() ? "missing" : "set");
            return;
        }
        this.botToken = token;
        this.chatId = chat;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        log.info("telegram notifications enabled (blocked={}, done={})",
                cfg.notifyBlocked(), cfg.notifyDone());
    }

    /** Синхронный CDI-observer: вызывается после каждого CONNECTED-кадра. */
    void onFrame(@Observes FrameApplied ev) {
        if (http == null) return;                            // выключено
        HostState prev = ev.prev();
        HostState now = ev.now();
        if (prev.health() == Health.UNREACHABLE) return;      // baseline: первый кадр / реконнект

        Map<String, String> prevStatus = new HashMap<>();
        for (AgentInfo a : prev.agents()) prevStatus.put(agentKey(a), a.status());

        for (AgentInfo a : now.agents()) {
            String st = a.status();
            if (Objects.equals(prevStatus.get(agentKey(a)), st)) continue;   // не изменилось
            boolean notify = ("blocked".equals(st) && cfg.notifyBlocked())
                    || ("done".equals(st) && cfg.notifyDone());
            if (notify) sendAsync(compose(now, a, st));
        }
    }

    /** Стабильный ключ агента в рамках сессии: pane_id, иначе workspace/title. */
    private static String agentKey(AgentInfo a) {
        if (a.paneId() != null && !a.paneId().isBlank()) return a.paneId();
        return a.workspaceId() + "/" + a.title();
    }

    private String compose(HostState h, AgentInfo a, String status) {
        String ws = a.workspaceId();
        for (WorkspaceInfo w : h.workspaces()) {
            if (Objects.equals(w.id(), a.workspaceId())) {
                ws = (w.label() != null && !w.label().isBlank()) ? w.label() : w.id();
                break;
            }
        }
        String head = "done".equals(status) ? "✅ done" : "⛔ needs input";
        String title = (a.title() != null && !a.title().isBlank()) ? a.title() : a.paneId();
        String kind = a.kind() != null ? a.kind() : "?";
        return head + " — " + h.id() + " · " + ws + " · " + title + " (" + kind + ")";
    }

    private void sendAsync(String text) {
        log.info("telegram: {}", text);   // фича проверяема даже без реальной доставки
        try {
            ObjectNode body = MAPPER.createObjectNode();
            body.put("chat_id", chatId);
            body.put("text", text);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.telegram.org/bot" + botToken + "/sendMessage"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                    .build();
            http.sendAsync(req, HttpResponse.BodyHandlers.ofString()).whenComplete((resp, err) -> {
                if (err != null) log.warn("telegram: send failed: {}", err.toString());
                else if (resp.statusCode() / 100 != 2)
                    log.warn("telegram: send HTTP {} — {}", resp.statusCode(), resp.body());
            });
        } catch (Exception e) {
            log.warn("telegram: send error: {}", e.toString());
        }
    }
}

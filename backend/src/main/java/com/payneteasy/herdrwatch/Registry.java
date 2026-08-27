package com.payneteasy.herdrwatch;

import com.payneteasy.herdrwatch.model.Model.HostState;
import com.payneteasy.herdrwatch.model.Model.Health;
import com.payneteasy.herdrwatch.model.Model.WorkspaceInfo;
import com.payneteasy.herdrwatch.model.Model.AgentInfo;
import com.payneteasy.herdrwatch.model.Model.StreamEvent;
import com.payneteasy.herdrwatch.usage.ClaudeUsage;

import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.operators.multi.processors.BroadcastProcessor;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Единственный источник истины для дашборда. Держит последнее известное
 * состояние каждого хоста и рассылает изменения всем SSE-подписчикам.
 *
 * Дашборд НИКОГДА не ходит к хостам напрямую — только сюда. Хост может
 * отваливаться и переподключаться, а UI всё равно видит консистентную картину.
 */
@ApplicationScoped
public class Registry {

    private static final Logger log = LoggerFactory.getLogger(Registry.class);

    private final Map<String, HostState> hosts = new ConcurrentHashMap<>();
    private final BroadcastProcessor<StreamEvent> bus = BroadcastProcessor.create();

    /**
     * Монотонный счётчик состояния, общий с SSE: инкрементится на каждую мутацию
     * (через {@link #emit}). Не убывает в пределах жизни процесса, сбрасывается при
     * рестарте. Используется Snapshot API как {@code sequence} и в {@code ETag}
     * (§3.3/§3.10 контракта). Содержимое SSE-событий и их вывод не меняет.
     */
    private final AtomicLong sequence = new AtomicLong(0);

    /** Внутреннее событие «применён CONNECTED-кадр» для наблюдателей (Telegram и т.п.). */
    @Inject Event<FrameApplied> frameEvents;

    /** Регистрируем хост в начальном состоянии (UNREACHABLE). */
    public void register(String id, String host) {
        HostState state = HostState.initial(id, host);
        hosts.put(id, state);
        emit("host_update", state);
    }

    /**
     * Применяем распарсенный кадр. ws/ag == null означает, что herdr на хосте
     * не ответил — соединение живо, но состояние DEGRADED (аналог твоего
     * `|| echo null` в удалённой jq-команде).
     */
    public void applyFrame(String id, Long ts,
                           List<WorkspaceInfo> ws, List<AgentInfo> ag,
                           boolean herdrOk) {
        HostState[] cap = new HostState[2];   // [prev, updated] — чтобы шлёт событие вне лока
        hosts.computeIfPresent(id, (k, prev) -> {
            Health h = herdrOk ? Health.CONNECTED : Health.DEGRADED;
            // при DEGRADED сохраняем предыдущий снапшот, не затираем пустотой
            List<WorkspaceInfo> newWs = herdrOk ? ws : prev.workspaces();
            List<AgentInfo> newAg = herdrOk ? ag : prev.agents();
            HostState updated = prev.withFrame(h, ts, newWs, newAg);
            emit("host_update", updated);
            cap[0] = prev;
            cap[1] = updated;
            return updated;
        });
        // только CONNECTED-кадры интересны наблюдателям (диффим статусы агентов)
        if (herdrOk && cap[1] != null) {
            frameEvents.fire(new FrameApplied(cap[0], cap[1]));
        }
    }

    /** Смена health (напр. UNREACHABLE при обрыве ssh). Снапшот сохраняется. */
    public void setHealth(String id, Health health) {
        hosts.computeIfPresent(id, (k, prev) -> {
            if (prev.health() == health) return prev;   // без лишних событий
            HostState updated = prev.withHealth(health);
            emit("host_update", updated);
            return updated;
        });
    }

    /** Удаляем хост из состояния (при DELETE через CRUD). Фронт убирает карточку. */
    public void remove(String id) {
        if (hosts.remove(id) != null) {
            emit("host_remove", Map.of("id", id));
        }
    }

    /**
     * Квота подписки Claude. Свойство аккаунта, а не хоста, поэтому лежит рядом с
     * картой хостов, а не в ней. volatile: пишет планировщик, читают HTTP-треды.
     */
    private volatile ClaudeUsage claudeUsage = ClaudeUsage.notConfigured();

    /**
     * Применяем свежий снапшот квоты. Неизменившийся снапшот НЕ рассылаем
     * (спека: «Unchanged data») — читатель тикает чаще, чем двигаются цифры,
     * а тик без изменений не должен ни будить клиентов, ни двигать sequence.
     */
    public void updateClaudeUsage(ClaudeUsage usage) {
        if (usage == null || usage.equals(claudeUsage)) return;
        claudeUsage = usage;
        emit("claude_usage", usage);
    }

    /** Текущая квота — для REST и Snapshot API (для тех, кто не держит SSE). */
    public ClaudeUsage claudeUsage() {
        return claudeUsage;
    }

    /** Полный снапшот для только что подключившегося SSE-клиента. */
    public List<HostState> snapshot() {
        return List.copyOf(hosts.values());
    }

    /** Поток событий для SSE. */
    public Multi<StreamEvent> events() {
        return bus;
    }

    /** Текущее значение монотонного счётчика состояния (для Snapshot API / ETag). */
    public long sequence() {
        return sequence.get();
    }

    private void emit(String type, Object data) {
        // любая мутация состояния = новый sequence (даже если SSE-подписчик упадёт ниже)
        sequence.incrementAndGet();
        // страховка: проблема любого SSE-подписчика не должна проникать в цикл опроса
        try {
            bus.onNext(new StreamEvent(type, data));
        } catch (Exception e) {
            log.warn("SSE emit dropped ({}): {}", type, e.toString());
        }
    }
}

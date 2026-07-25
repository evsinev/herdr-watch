package com.payneteasy.herdrwatch.http;

import com.payneteasy.herdrwatch.Registry;
import com.payneteasy.herdrwatch.model.Model.StreamEvent;

import io.smallrye.mutiny.Multi;
import jakarta.inject.Inject;

import java.time.Duration;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import org.jboss.resteasy.reactive.RestSseElementType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * GET /api/stream — SSE-поток для дашборда.
 *
 * При подключении сразу отдаём полный снапшот (событие type=snapshot),
 * затем стримим дельты (type=host_update). Браузерный EventSource
 * переподключается сам при обрыве и снова получит свежий снапшот.
 */
@Path("/api/stream")
public class StreamResource {

    private static final Logger log = LoggerFactory.getLogger(StreamResource.class);

    @Inject Registry registry;

    @GET
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestSseElementType(MediaType.APPLICATION_JSON)
    public Multi<StreamEvent> stream() {
        log.debug("SSE client connected");
        StreamEvent snapshot = new StreamEvent("snapshot", registry.snapshot());
        // onOverflow().drop(): медленный/отвалившийся клиент ТЕРЯЕТ кадры, а не роняет
        // broadcast с BackPressureFailure. Для живого монитора это ок — следующий
        // host_update несёт актуальное состояние.
        //
        // Heartbeat: раз в 15с шлём type=ping. Когда вся ферма «тихая» (все хосты
        // UNREACHABLE/idle), host_update'ов нет — без пинга клиент с request-timeout
        // (нативный трей) решил бы, что соединение мертво, и ложно переподключался бы.
        // ticks холодный → у каждого SSE-клиента свой heartbeat; клиенты type=ping игнорируют.
        Multi<StreamEvent> live = Multi.createBy().merging().streams(
                registry.events().onOverflow().drop(),
                Multi.createFrom().ticks().every(Duration.ofSeconds(15))
                        .onOverflow().drop()
                        .map(tick -> new StreamEvent("ping", null))
        );
        return Multi.createBy().concatenating().streams(
                Multi.createFrom().item(snapshot),
                live
        );
    }
}

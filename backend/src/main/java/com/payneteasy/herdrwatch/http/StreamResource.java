package com.payneteasy.herdrwatch.http;

import com.payneteasy.herdrwatch.Registry;
import com.payneteasy.herdrwatch.model.Model.StreamEvent;

import io.smallrye.mutiny.Multi;
import jakarta.inject.Inject;
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
        return Multi.createBy().concatenating().streams(
                Multi.createFrom().item(snapshot),
                registry.events().onOverflow().drop()
        );
    }
}

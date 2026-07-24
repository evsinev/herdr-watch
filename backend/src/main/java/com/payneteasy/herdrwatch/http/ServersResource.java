package com.payneteasy.herdrwatch.http;

import com.payneteasy.herdrwatch.HostStore;
import com.payneteasy.herdrwatch.Registry;
import com.payneteasy.herdrwatch.model.HostDef;
import com.payneteasy.herdrwatch.model.Model.Health;
import com.payneteasy.herdrwatch.model.Model.HostState;
import com.payneteasy.herdrwatch.source.SourceManager;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * /api/servers — управление списком хостов.
 *
 * GET    — список хостов с конфигом и живым health (для таблицы Settings).
 * POST   — добавить хост (и сразу поднять источник, если enabled).
 * PUT    — изменить хост (стоп + старт с новыми параметрами, без рестарта приложения).
 * DELETE — удалить хост (остановить источник, убрать карточку из Monitor).
 *
 * Правки пишутся в state-файл поверх bootstrap-конфига (см. {@link HostStore}),
 * подключение меняется на лету (см. {@link SourceManager}).
 */
@Path("/api/servers")
@Produces(MediaType.APPLICATION_JSON)
public class ServersResource {

    @Inject HostStore hostStore;
    @Inject SourceManager sourceManager;
    @Inject Registry registry;

    /** Входной DTO CRUD-запроса (числа boxed — чтобы отличить «не задано» от 0). */
    public record HostRequest(
            String id,
            String host,
            String herdrPath,
            Integer pollInterval,
            Integer reconnectDelay,
            Boolean enabled,
            String sshExtraOpts,
            Boolean local
    ) {}

    /** Выходной DTO для Settings: конфиг + живой health. */
    public record ServerView(
            String id,
            String host,
            String herdrPath,
            int pollInterval,
            int reconnectDelay,
            boolean enabled,
            String sshExtraOpts,
            boolean local,
            String health,
            Long lastUpdate
    ) {}

    @GET
    public List<ServerView> list() {
        Map<String, HostState> live = liveById();
        return hostStore.all().stream().map(d -> toView(d, live)).toList();
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response create(HostRequest req) {
        Map<String, String> errors = validate(req, true);
        if (!errors.isEmpty()) return badRequest(errors);

        HostDef def = toDef(req.id().trim(), req);
        hostStore.add(def);
        if (def.enabled()) sourceManager.startHost(def);

        return Response.status(Response.Status.CREATED)
                .entity(toView(def, liveById()))
                .build();
    }

    @PUT
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response update(@PathParam("id") String id, HostRequest req) {
        if (!hostStore.exists(id)) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("errors", Map.of("id", "No host with this id")))
                    .build();
        }
        Map<String, String> errors = validate(req, false);
        if (!errors.isEmpty()) return badRequest(errors);

        // id неизменяем — берём из пути, тело id игнорируем
        HostDef def = toDef(id, req);
        hostStore.update(id, def);
        sourceManager.restartHost(def);   // сам решит: стоп+старт или только стоп (если disabled)

        return Response.ok(toView(def, liveById())).build();
    }

    @DELETE
    @Path("/{id}")
    public Response delete(@PathParam("id") String id) {
        if (!hostStore.exists(id)) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        hostStore.remove(id);
        sourceManager.stopHost(id);
        return Response.noContent().build();
    }

    // --- helpers ---

    private Map<String, HostState> liveById() {
        Map<String, HostState> m = new LinkedHashMap<>();
        for (HostState st : registry.snapshot()) m.put(st.id(), st);
        return m;
    }

    private ServerView toView(HostDef d, Map<String, HostState> live) {
        HostState st = live.get(d.id());
        String health = (st != null) ? st.health().name() : Health.UNREACHABLE.name();
        Long lastUpdate = (st != null) ? st.lastUpdate() : null;
        return new ServerView(
                d.id(), d.host(), d.herdrPath(),
                d.pollInterval(), d.reconnectDelay(), d.enabled(), d.sshExtraOpts(), d.local(),
                health, lastUpdate
        );
    }

    private HostDef toDef(String id, HostRequest r) {
        boolean local = r.local() != null && r.local();
        String herdrPath = blank(r.herdrPath()) ? "herdr" : r.herdrPath().trim();
        String sshExtra = blank(r.sshExtraOpts()) ? null : r.sshExtraOpts().trim();
        boolean enabled = (r.enabled() == null) ? true : r.enabled();
        // для local ssh-таргет не нужен — ставим ярлык "local" для отображения
        String host = blank(r.host()) ? (local ? "local" : "") : r.host().trim();
        return new HostDef(id, host, herdrPath,
                r.pollInterval(), r.reconnectDelay(), enabled, sshExtra, local);
    }

    /** Валидация в «голосе интерфейса» — тексты совпадают с формой из макета. */
    private Map<String, String> validate(HostRequest r, boolean isNew) {
        Map<String, String> e = new LinkedHashMap<>();
        if (blank(r.id())) {
            e.put("id", "Enter a name for this host");
        } else if (isNew && hostStore.exists(r.id().trim())) {
            e.put("id", "A host with this id already exists");
        }
        boolean local = r.local() != null && r.local();
        if (!local && blank(r.host())) {
            e.put("host", "Enter an ssh target");
        }
        if (r.pollInterval() == null || r.pollInterval() < 1) {
            e.put("pollInterval", "Use a positive whole number");
        }
        if (r.reconnectDelay() == null || r.reconnectDelay() < 1) {
            e.put("reconnectDelay", "Use a positive whole number");
        }
        return e;
    }

    private Response badRequest(Map<String, String> errors) {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(Map.of("errors", errors))
                .build();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}

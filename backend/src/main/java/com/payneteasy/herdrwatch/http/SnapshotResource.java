package com.payneteasy.herdrwatch.http;

import com.payneteasy.herdrwatch.Registry;
import com.payneteasy.herdrwatch.snapshot.SnapshotAgent;
import com.payneteasy.herdrwatch.snapshot.SnapshotError;
import com.payneteasy.herdrwatch.snapshot.SnapshotProjection;
import com.payneteasy.herdrwatch.snapshot.SnapshotReadiness;
import com.payneteasy.herdrwatch.snapshot.SnapshotResponseCompact;
import com.payneteasy.herdrwatch.snapshot.SnapshotResponseFull;
import com.payneteasy.herdrwatch.snapshot.SnapshotResponseStatus;
import com.payneteasy.herdrwatch.snapshot.SnapshotTime;
import com.payneteasy.herdrwatch.snapshot.SnapshotUsage;

import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;

import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Snapshot API (v1) — read-only снапшот состояния всех агентов одним GET, без SSE.
 * Контракт: {@code docs/api/herdr-watch-snapshot-protocol.md}. Дополнение к SSE, не замена;
 * существующие эндпоинты ({@code /api/stream}, {@code /api/servers}) не меняются.
 *
 * <p>Проекция — чистая, только чтение {@link Registry#snapshot()} + {@link Registry#sequence()},
 * без побочных эффектов и блокирующих вызовов наружу (§3).
 */
@Path("/api/v1/snapshot")
@Produces(SnapshotResource.JSON)
@Tag(name = "Snapshot", description = "Read-only снапшот состояния агентов (v1)")
public class SnapshotResource {

    static final String JSON = "application/json;charset=UTF-8";
    private static final int PROTOCOL_VERSION = 1;
    private static final int LIMIT_MIN = 1;
    private static final int LIMIT_MAX = 200;
    private static final ZoneId GMT = ZoneId.of("GMT");

    @Inject Registry registry;
    @Inject SnapshotReadiness readiness;

    private enum View { FULL, COMPACT, STATUS }

    @GET
    @Path("/agents")
    @Operation(summary = "Снапшот агентов",
            description = "Плоский снапшот состояния всех агентов. Профиль выбирается параметром view (§3.5).")
    // anyOf (не oneOf): full-ответ проходит и по схеме compact (её поля — подмножество,
    // а модель контента открытая), поэтому oneOf «совпало ровно одно» ложно бы падал.
    @APIResponse(responseCode = "200", description = "успех",
            content = @Content(mediaType = JSON,
                    schema = @Schema(anyOf = {
                            SnapshotResponseFull.class,
                            SnapshotResponseCompact.class,
                            SnapshotResponseStatus.class})))
    @APIResponse(responseCode = "304", description = "If-None-Match совпал с текущим ETag; тело отсутствует")
    @APIResponse(responseCode = "400", description = "limit вне диапазона/не число или неизвестный view",
            content = @Content(schema = @Schema(implementation = SnapshotError.class)))
    @APIResponse(responseCode = "503", description = "сервис не завершил первичную инициализацию",
            content = @Content(schema = @Schema(implementation = SnapshotError.class)))
    public Response agents(
            @Parameter(description = "максимум записей после сортировки, 1..200")
            @QueryParam("limit") @DefaultValue("200") String limitRaw,
            @Parameter(description = "профиль ответа: full | compact | status")
            @QueryParam("view") @DefaultValue("full") String viewRaw,
            @HeaderParam("If-None-Match") String ifNoneMatch) {

        if (!readiness.isReady()) return notReady();

        Integer limit = parseLimit(limitRaw);
        if (limit == null) {
            return badRequest("limit must be between " + LIMIT_MIN + " and " + LIMIT_MAX);
        }
        View view = parseView(viewRaw);
        if (view == null) {
            return badRequest("unknown view: " + viewRaw);
        }

        // sequence читаем один раз — он идёт и в ETag, и в тело (§3.10).
        long seq = registry.sequence();
        String etag = "\"" + view.name().toLowerCase() + "-l" + limit + "-" + seq + "\"";

        // Условный запрос: If-None-Match совпал → 304 без тела, с ETag (и Date) (§3.10).
        if (etag.equals(ifNoneMatch)) {
            return Response.status(Response.Status.NOT_MODIFIED)
                    .header(HttpHeaders.ETAG, etag)
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .header(HttpHeaders.DATE, httpDate())
                    .build();
        }

        List<SnapshotAgent> all = SnapshotProjection.projectFull(registry.snapshot());
        int total = all.size();
        List<SnapshotAgent> page = total > limit ? all.subList(0, limit) : all;
        int count = page.size();

        Object body = switch (view) {
            case FULL -> new SnapshotResponseFull(
                    PROTOCOL_VERSION, seq, count, total, List.copyOf(page));
            case COMPACT -> new SnapshotResponseCompact(
                    PROTOCOL_VERSION, seq, count, total,
                    page.stream().map(SnapshotProjection::toCompact).toList());
            case STATUS -> new SnapshotResponseStatus(
                    PROTOCOL_VERSION, seq, count, total,
                    page.stream().map(SnapshotProjection::toStatus).toList());
        };

        return Response.ok(body)
                .type(JSON)
                .header(HttpHeaders.ETAG, etag)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.DATE, httpDate())
                .build();
    }

    @GET
    @Path("/time")
    @Operation(summary = "Время сервера",
            description = "Часовой пояс и смещение сервера (§4). Без ETag; Date присутствует, как у всех ответов.")
    @APIResponse(responseCode = "200", description = "успех",
            content = @Content(schema = @Schema(implementation = SnapshotTime.class)))
    @APIResponse(responseCode = "503", description = "сервис не завершил первичную инициализацию",
            content = @Content(schema = @Schema(implementation = SnapshotError.class)))
    public Response time() {
        if (!readiness.isReady()) return notReady();

        ZonedDateTime now = ZonedDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        SnapshotTime body = new SnapshotTime(
                PROTOCOL_VERSION,
                now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                now.toEpochSecond(),
                now.getZone().getId(),
                now.getOffset().getTotalSeconds());
        return Response.ok(body).type(JSON).header(HttpHeaders.DATE, httpDate()).build();
    }

    @GET
    @Path("/usage")
    @Operation(summary = "Квота подписки Claude",
            description = "Утилизация 5-часового и недельного окон (§9). Окно, которое не "
                    + "отчиталось, в массив не попадает. ETag = \"usage-<capturedAt>\".")
    @APIResponse(responseCode = "200", description = "успех",
            content = @Content(schema = @Schema(implementation = SnapshotUsage.class)))
    @APIResponse(responseCode = "304", description = "If-None-Match совпал с текущим ETag; тело отсутствует")
    @APIResponse(responseCode = "503", description = "сервис не завершил первичную инициализацию",
            content = @Content(schema = @Schema(implementation = SnapshotError.class)))
    public Response usage(@HeaderParam("If-None-Match") String ifNoneMatch) {
        if (!readiness.isReady()) return notReady();

        SnapshotUsage body = SnapshotProjection.projectUsage(PROTOCOL_VERSION, registry.claudeUsage());
        // Валидатор — время снятия показаний: тело меняется ровно тогда, когда появляется
        // новая запись хука (§3.10). Клиент, опрашивающий чаще, чем идут показания,
        // получает 304 без тела — а это здесь обычный случай.
        String etag = "\"usage-" + body.capturedAt() + "\"";

        if (etag.equals(ifNoneMatch)) {
            return Response.status(Response.Status.NOT_MODIFIED)
                    .header(HttpHeaders.ETAG, etag)
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .header(HttpHeaders.DATE, httpDate())
                    .build();
        }

        return Response.ok(body)
                .type(JSON)
                .header(HttpHeaders.ETAG, etag)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.DATE, httpDate())
                .build();
    }

    // --- helpers ---

    /** RFC 9110 Date в GMT, секундная точность (§2). Ставим сами — Vert.x его тут не добавляет. */
    private static String httpDate() {
        return DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now(GMT));
    }

    private static Integer parseLimit(String raw) {
        if (raw == null) return null;
        try {
            int v = Integer.parseInt(raw.trim());
            return (v >= LIMIT_MIN && v <= LIMIT_MAX) ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static View parseView(String raw) {
        if (raw == null) return View.FULL;
        switch (raw.trim().toLowerCase()) {   // без учёта регистра (§3.1)
            case "full": return View.FULL;
            case "compact": return View.COMPACT;
            case "status": return View.STATUS;
            default: return null;
        }
    }

    private Response badRequest(String message) {
        return Response.status(Response.Status.BAD_REQUEST)
                .type(JSON)
                .header(HttpHeaders.DATE, httpDate())
                .entity(new SnapshotError("invalid_parameter", message))
                .build();
    }

    private Response notReady() {
        return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                .type(JSON)
                .header(HttpHeaders.DATE, httpDate())
                .entity(new SnapshotError("not_ready", "service is still initializing"))
                .build();
    }
}

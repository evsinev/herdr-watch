package com.payneteasy.herdrwatch.http;

import com.payneteasy.herdrwatch.Registry;
import com.payneteasy.herdrwatch.usage.ClaudeUsage;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * GET /api/claude-usage — текущая квота подписки Claude для клиентов без SSE
 * (и для только что подключившихся: событие {@code claude_usage} приходит лишь
 * при изменении, а не на каждый коннект).
 *
 * <p>Данные те же, что в SSE-событии, — оба берут {@link Registry#claudeUsage()}.
 */
@Path("/api/claude-usage")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Claude usage", description = "Квота подписки Claude (5-часовое и недельное окна)")
public class ClaudeUsageResource {

    @Inject Registry registry;

    @GET
    @Operation(summary = "Текущая квота Claude",
            description = "Последний снапшот квоты. Если хук не установлен — state = NOT_CONFIGURED.")
    @APIResponse(responseCode = "200", description = "успех",
            content = @Content(schema = @Schema(implementation = ClaudeUsage.class)))
    public ClaudeUsage current() {
        return registry.claudeUsage();
    }
}

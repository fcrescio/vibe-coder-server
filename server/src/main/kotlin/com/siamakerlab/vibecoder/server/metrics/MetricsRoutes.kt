package com.siamakerlab.vibecoder.server.metrics

import com.siamakerlab.vibecoder.server.admin.AdminRoutesDeps
import com.siamakerlab.vibecoder.server.admin.requireAdminOrRedirect
import com.siamakerlab.vibecoder.server.admin.requireSessionOrRedirect
import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get

/**
 * v0.55.0 — Phase 34 `/metrics` endpoint (Prometheus exposition format).
 *
 * Admin-only. For internal scraping behind a reverse proxy you can fronted the path
 * with basic-auth at the gateway and let the Bearer / cookie role check sit behind it.
 *
 * Content-Type follows the Prometheus convention: `text/plain; version=0.0.4`.
 */
fun Routing.metricsRoutes(authDeps: AdminRoutesDeps, registry: MetricsRegistry) {
    get("/metrics") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@get
        if (!requireAdminOrRedirect(sess)) return@get
        call.respondText(
            registry.render(),
            ContentType.parse("text/plain; version=0.0.4; charset=utf-8"),
        )
    }
}

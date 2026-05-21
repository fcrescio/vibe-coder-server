package com.siamakerlab.vibecoder.server.error

import com.siamakerlab.vibecoder.shared.dto.ApiErrorDto
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.JsonConvertException
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.httpMethod
import io.ktor.server.request.uri
import io.ktor.server.response.respond
import io.github.oshai.kotlinlogging.KotlinLogging

private val log = KotlinLogging.logger {}

fun Application.installStatusPages() {
    install(StatusPages) {
        exception<ApiException> { call, cause ->
            call.respond(
                HttpStatusCode.fromValue(cause.statusCode),
                ApiErrorDto(code = cause.code, message = cause.message ?: cause.code, detail = cause.detail),
            )
        }
        // kotlinx-serialization wraps MissingFieldException etc. into JsonConvertException.
        // Map to 400 with the original message so clients see exactly which field is wrong.
        exception<JsonConvertException> { call, cause ->
            val msg = cause.cause?.message ?: cause.message ?: "invalid JSON body"
            call.respond(
                HttpStatusCode.BadRequest,
                ApiErrorDto(code = "bad_request", message = msg),
            )
        }
        // Ktor also throws BadRequestException for parameter / receive failures.
        exception<BadRequestException> { call, cause ->
            call.respond(
                HttpStatusCode.BadRequest,
                ApiErrorDto(code = "bad_request", message = cause.message ?: "bad request"),
            )
        }
        // require() inside services throws IllegalArgumentException — surface as 400.
        exception<IllegalArgumentException> { call, cause ->
            call.respond(
                HttpStatusCode.BadRequest,
                ApiErrorDto(code = "bad_request", message = cause.message ?: "illegal argument"),
            )
        }
        exception<Throwable> { call, cause ->
            log.error(cause) { "unhandled error: ${cause.message}" }
            call.respond(
                HttpStatusCode.InternalServerError,
                ApiErrorDto(code = "internal_error", message = cause.message ?: "internal_error"),
            )
        }
        // Catch-all route miss: log the actual method + uri so client-side URL bugs
        // are diagnosable from server logs, and return a structured ApiErrorDto so
        // clients can deserialize uniformly.
        status(HttpStatusCode.NotFound) { call, status ->
            val method = call.request.httpMethod.value
            val uri = call.request.uri
            log.warn { "route miss: $method $uri" }
            call.respond(
                status,
                ApiErrorDto(code = "route_not_found", message = "$method $uri has no matching route"),
            )
        }
    }
}

package com.siamakerlab.vibecoder.server.error

/**
 * Domain exception that maps to a JSON `ApiErrorDto` and an HTTP [statusCode].
 * Throw freely from services; [StatusPagesPlugin] converts to a response.
 *
 * v0.92.0 — Phase 67 i18n. Optional [messageKey] + [messageArgs] for response-time
 * localization via `Messages.t(lang, messageKey, ...messageArgs)`. Backward
 * compatible — existing callers using positional [message] keep working
 * (English-only). New callers using [messageKey] benefit from Accept-Language.
 *
 * Two factories:
 *   - `ApiException(statusCode, code, message, detail)` — legacy English-only.
 *   - `ApiException.localized(statusCode, code, messageKey, args = ..., detail = ...)` —
 *     i18n-aware. The exception's [message] is the English version (for logs);
 *     StatusPagesPlugin re-renders for the response in the request's language.
 */
class ApiException private constructor(
    val statusCode: Int,
    val code: String,
    message: String,
    val detail: String? = null,
    /** v0.92.0 — i18n key. If non-null, StatusPagesPlugin localizes at response time. */
    val messageKey: String? = null,
    /** Args for `Messages.t(lang, messageKey, *args)`. Empty if no parametric subs. */
    val messageArgs: List<Any?> = emptyList(),
) : RuntimeException(message) {

    /** Legacy constructor — English-only. Use [localized] for new i18n call sites. */
    constructor(
        statusCode: Int,
        code: String,
        message: String,
        detail: String? = null,
    ) : this(statusCode, code, message, detail, messageKey = null, messageArgs = emptyList())

    companion object {
        /**
         * v0.92.0 — i18n-aware factory. [messageKey] resolves via Messages at response
         * time. The exception's `message` field carries the English fallback for logs.
         *
         * Example:
         * ```
         * throw ApiException.localized(400, "username_required",
         *     messageKey = "api.auth.usernameRequired")
         * ```
         */
        fun localized(
            statusCode: Int,
            code: String,
            messageKey: String,
            args: List<Any?> = emptyList(),
            detail: String? = null,
        ): ApiException {
            // English fallback for log lines + non-i18n consumers (legacy server defaults).
            val fallback = com.siamakerlab.vibecoder.server.i18n.Messages.t(
                com.siamakerlab.vibecoder.server.i18n.Messages.DEFAULT, messageKey, *args.toTypedArray(),
            )
            return ApiException(
                statusCode = statusCode,
                code = code,
                message = fallback,
                detail = detail,
                messageKey = messageKey,
                messageArgs = args,
            )
        }
    }
}

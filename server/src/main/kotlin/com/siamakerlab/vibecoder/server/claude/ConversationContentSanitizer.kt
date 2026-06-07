package com.siamakerlab.vibecoder.server.claude

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

internal object ConversationContentSanitizer {
    private const val LARGE_STRING_LIMIT = 8_192
    private const val LARGE_JSON_LIMIT = 64_000

    private val json = Json { ignoreUnknownKeys = true }

    fun jsonString(element: JsonElement): String {
        val sanitized = sanitize(element)
        val encoded = sanitized.toString()
        return if (encoded.length <= LARGE_JSON_LIMIT) {
            encoded
        } else {
            json.encodeToString(
                JsonObject.serializer(),
                JsonObject(
                    mapOf(
                        "omitted" to JsonPrimitive("large tool payload"),
                        "originalChars" to JsonPrimitive(encoded.length),
                    ),
                ),
            )
        }
    }

    private fun sanitize(element: JsonElement): JsonElement =
        when (element) {
            is JsonObject -> sanitizeObject(element)
            is JsonArray -> JsonArray(element.map(::sanitize))
            else -> element
        }

    private fun sanitizeObject(obj: JsonObject): JsonObject {
        val looksLikeImage = obj["mimeType"].isImageMime() ||
            obj["mime_type"].isImageMime() ||
            obj["mime"].isImageMime()

        return JsonObject(
            obj.mapValues { (key, value) ->
                when {
                    key == "data" && value.isLargeString() ->
                        JsonPrimitive(omitted("base64 data", value.stringLength()))
                    key == "imageData" && value.isLargeString() ->
                        JsonPrimitive(omitted("base64 image data", value.stringLength()))
                    looksLikeImage && key.lowercase().contains("data") && value.isLargeString() ->
                        JsonPrimitive(omitted("image data", value.stringLength()))
                    key == "rawOutput" && value.isLargeString() ->
                        sanitizeRawOutput(value.jsonPrimitive.content)
                    else -> sanitize(value)
                }
            },
        )
    }

    private fun sanitizeRawOutput(raw: String): JsonElement {
        val parsed = runCatching { json.parseToJsonElement(raw) }.getOrNull()
        if (parsed != null) {
            val sanitized = sanitize(parsed).toString()
            if (sanitized.length <= LARGE_STRING_LIMIT) {
                return JsonPrimitive(sanitized)
            }
        }
        return JsonPrimitive(omitted("large rawOutput", raw.length))
    }

    private fun JsonElement?.isImageMime(): Boolean =
        this is JsonPrimitive && contentOrNull?.lowercase()?.startsWith("image/") == true

    private fun JsonElement.isLargeString(): Boolean =
        this is JsonPrimitive &&
            !isStringBackedScalar() &&
            (contentOrNull?.length ?: 0) > LARGE_STRING_LIMIT

    private fun JsonElement.stringLength(): Int =
        (this as? JsonPrimitive)?.contentOrNull?.length ?: 0

    private fun JsonPrimitive.isStringBackedScalar(): Boolean =
        this is JsonNull ||
            booleanOrNull != null ||
            longOrNull != null ||
            doubleOrNull != null

    private fun omitted(kind: String, chars: Int): String =
        "[omitted $kind from persisted history: $chars chars]"
}

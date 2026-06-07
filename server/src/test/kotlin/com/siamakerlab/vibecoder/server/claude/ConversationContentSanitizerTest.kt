package com.siamakerlab.vibecoder.server.claude

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationContentSanitizerTest {

    @Test
    fun jsonString_omitsLargeImageDataFields() {
        val largeImage = "a".repeat(20_000)
        val element = Json.parseToJsonElement(
            """
            {
              "kind": "other",
              "rawOutput": "{\"serial\":\"device-1\",\"mime_type\":\"image/png\",\"data\":\"$largeImage\"}",
              "content": [{"content": {"text": "Captured screenshot.", "type": "text"}, "type": "content"}]
            }
            """.trimIndent(),
        )

        val sanitized = ConversationContentSanitizer.jsonString(element)

        assertFalse(sanitized.contains(largeImage))
        assertTrue(sanitized.contains("omitted"))
        assertTrue(sanitized.contains("20000 chars"))
        assertTrue(sanitized.contains("Captured screenshot."))
    }

    @Test
    fun jsonString_preservesSmallToolResults() {
        val element = Json.parseToJsonElement("""{"ok":true,"message":"Tapped 270,573."}""")

        val sanitized = ConversationContentSanitizer.jsonString(element)
        val obj = Json.parseToJsonElement(sanitized).jsonObject

        assertTrue(obj["ok"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(obj["message"]!!.jsonPrimitive.content == "Tapped 270,573.")
    }
}

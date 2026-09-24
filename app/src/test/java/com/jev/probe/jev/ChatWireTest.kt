package com.jev.probe.jev

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ChatWireTest {
    private fun config(p: ChatProtocol) = ChatApiConfig(p, "https://gateway.example/proxy/v1/", "test-token", "user-model-id")

    @Test fun openAiHasChatMessagesAndBearerAuth() {
        val r = ChatWire.request(config(ChatProtocol.OPENAI), "系统", "用户")
        assertEquals("https://gateway.example/proxy/v1/chat/completions", r.url)
        assertEquals(HttpJson.AuthStyle.BEARER, r.auth)
        assertEquals("system", r.body.getJSONArray("messages").getJSONObject(0).getString("role"))
        assertEquals("user", r.body.getJSONArray("messages").getJSONObject(1).getString("role"))
        assertFalse(r.body.has("system"))
        assertFalse(r.body.has("temperature"))
        assertFalse(r.body.has("response_format"))
        assertEquals(2048, r.body.getInt("max_completion_tokens"))
        assertFalse(r.body.has("max_tokens"))
    }
    @Test fun anthropicHasTopLevelSystemVersionAndRequiredTokenBudget() {
        val r = ChatWire.request(config(ChatProtocol.ANTHROPIC), "系统", "用户")
        assertEquals("https://gateway.example/proxy/v1/messages", r.url)
        assertEquals(HttpJson.AuthStyle.ANTHROPIC_API_KEY, r.auth)
        assertEquals("2023-06-01", r.headers["anthropic-version"])
        assertEquals("系统", r.body.getString("system"))
        assertEquals(1, r.body.getJSONArray("messages").length())
        assertEquals(2048, r.body.getInt("max_tokens"))
        assertFalse(r.body.has("max_completion_tokens"))
    }
    @Test fun rootVersionAndFullEndpointsNormalizeWithoutDoubleSuffixes() {
        for (p in ChatProtocol.values()) {
            val suffix = if (p == ChatProtocol.OPENAI) "chat/completions" else "messages"
            for (base in listOf("https://api.example", "https://api.example/v1", "https://api.example/v1/", "https://api.example/v1/${suffix}")) {
                assertEquals("https://api.example/v1/${suffix}", ApiEndpoints.chat(base, p))
            }
        }
    }
    @Test fun badOrMismatchedEndpointsAreRejected() {
        for (url in listOf("", "http://api.example", "https://u:password@api.example", "https://api.example?v=1", "https://api.example#key", "https://api.example/v1/responses", "https://api.example/v1/messages")) {
            assertThrows(Exception::class.java) { ApiEndpoints.chat(url, ChatProtocol.OPENAI) }
        }
        assertThrows(Exception::class.java) { ApiEndpoints.chat("https://api.example/v1/chat/completions", ChatProtocol.ANTHROPIC) }
    }
    @Test fun openAiTextAndContentPartsDecode() {
        assertEquals("收到", ChatWire.text(ChatProtocol.OPENAI, JSONObject("""{"choices":[{"finish_reason":"stop","message":{"content":"收到"}}]}""")))
        assertEquals("收到", ChatWire.text(ChatProtocol.OPENAI, JSONObject("""{"choices":[{"finish_reason":"stop","message":{"content":[{"type":"text","text":"收到"}]}}]}""")))
    }
    @Test fun anthropicIgnoresThinkingAndJoinsOnlyTextBlocks() {
        val o = JSONObject("""{"stop_reason":"end_turn","content":[{"type":"thinking","thinking":"do not expose"},{"type":"text","text":"甲"},{"type":"text","text":"乙"}]}""")
        assertEquals("甲\n乙", ChatWire.text(ChatProtocol.ANTHROPIC, o))
    }
    @Test fun truncationRefusalToolsAndEmptyTextAreNotSuccess() {
        for (reason in listOf("max_tokens", "tool_use", "refusal", "pause_turn")) {
            assertThrows(Exception::class.java) { ChatWire.text(ChatProtocol.ANTHROPIC, JSONObject("""{"stop_reason":"$reason","content":[{"type":"text","text":"incomplete"}]}""")) }
        }
        for (reason in listOf("length", "content_filter", "tool_calls")) {
            assertThrows(Exception::class.java) { ChatWire.text(ChatProtocol.OPENAI, JSONObject("""{"choices":[{"finish_reason":"$reason","message":{"content":"incomplete"}}]}""")) }
        }
        assertThrows(Exception::class.java) { ChatWire.text(ChatProtocol.OPENAI, JSONObject("""{"choices":[{"message":{"content":null,"refusal":"no"}}]}""")) }
        assertThrows(Exception::class.java) { ChatWire.text(ChatProtocol.OPENAI, JSONObject("{}")) }
    }
    @Test fun tokenFallbackOnlyForExplicitUnsupportedParameter() {
        assertTrue(ChatWire.needsLegacyTokenLimit(ApiException("test", 400, "unknown parameter max_completion_tokens")))
        assertFalse(ChatWire.needsLegacyTokenLimit(ApiException("test", 401, "unknown parameter max_completion_tokens")))
        assertFalse(ChatWire.needsLegacyTokenLimit(ApiException("test", 400, "max_completion_tokens too high")))
        assertFalse(ChatWire.needsLegacyTokenLimit(ApiException("test", 429, "busy")))
    }
    @Test fun noBlankCredentialsModelsOrHeaderInjection() {
        assertThrows(Exception::class.java) { ChatWire.request(config(ChatProtocol.OPENAI).copy(model = ""), "s", "u") }
        assertThrows(Exception::class.java) { ChatWire.request(config(ChatProtocol.OPENAI).copy(key = ""), "s", "u") }
        assertThrows(Exception::class.java) { ChatWire.request(config(ChatProtocol.ANTHROPIC).copy(key = "x\ny"), "s", "u") }
    }
    @Test fun originComparisonDoesNotTreatLookalikeHostsAsSameService() {
        assertTrue(ApiEndpoints.sameOrigin("https://API.example/v1", "https://api.example:443/alpha"))
        assertFalse(ApiEndpoints.sameOrigin("https://api.example", "https://api.example.attacker.invalid"))
        assertFalse(ApiEndpoints.sameOrigin("https://api.example", "http://api.example"))
        assertFalse(ApiEndpoints.sameOrigin("https://api.example:8443", "https://api.example"))
        assertFalse(ApiEndpoints.sameOrigin("", ""))
    }
}

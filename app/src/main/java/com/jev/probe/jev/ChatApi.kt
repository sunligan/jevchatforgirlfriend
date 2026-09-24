package com.jev.probe.jev

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/** A snapshot of one route: settings changes cannot switch providers halfway through a request. */
data class ChatApiConfig(val protocol: ChatProtocol, val baseUrl: String, val key: String, val model: String)

data class ChatRequest(val url: String, val body: JSONObject, val headers: Map<String, String>, val auth: HttpJson.AuthStyle)

object ApiEndpoints {
    fun chat(baseUrl: String, protocol: ChatProtocol): String {
        val uri = URI(baseUrl.trim())
        require(uri.scheme == "https" && !uri.host.isNullOrBlank()) { "接口地址必须是有效的 HTTPS 地址" }
        require(uri.userInfo == null && uri.query == null && uri.fragment == null) { "接口地址不能带用户名、查询参数或片段" }
        val path = uri.path.orEmpty().trimEnd('/')
        val leaf = if (protocol == ChatProtocol.OPENAI) "/chat/completions" else "/messages"
        val opposite = if (protocol == ChatProtocol.OPENAI) "/messages" else "/chat/completions"
        require(!path.endsWith(opposite) && !path.endsWith("/responses")) { "所选协议与地址不匹配；OpenAI 模式使用 Chat Completions，不是 Responses" }
        val full = when {
            path.endsWith(leaf) -> path
            path.endsWith("/v1") -> path + leaf
            else -> path + "/v1" + leaf
        }
        return URI(uri.scheme, null, uri.host, uri.port, full, null, null).toASCIIString()
    }

    fun sameOrigin(a: String, b: String): Boolean = runCatching {
        val x = URI(a.trim()); val y = URI(b.trim())
        fun port(u: URI) = if (u.port != -1) u.port else if (u.scheme == "https") 443 else 80
        !x.host.isNullOrBlank() && x.scheme == y.scheme && x.host.equals(y.host, true) && port(x) == port(y)
    }.getOrDefault(false)
}

/** Pure wire construction/decoding, independently tested for both APIs. */
object ChatWire {
    const val ANTHROPIC_VERSION = "2023-06-01"

    fun request(config: ChatApiConfig, system: String, user: String, maxTokens: Int = 2048): ChatRequest {
        require(config.key.isNotBlank() && !config.key.contains('\n') && !config.key.contains('\r')) { "请填写有效 API 密钥" }
        require(config.model.isNotBlank()) { "请填写服务商提供的模型 ID" }
        require(maxTokens in 1..16384)
        val url = ApiEndpoints.chat(config.baseUrl, config.protocol)
        val body = JSONObject().put("model", config.model.trim()).put("stream", false)
        return when (config.protocol) {
            ChatProtocol.OPENAI -> {
                body.put("max_completion_tokens", maxTokens).put("messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content", system))
                    .put(JSONObject().put("role", "user").put("content", user)))
                // No temperature/response_format: many reasoning/compatible endpoints reject them.
                ChatRequest(url, body, HttpJson.headersFor(url), HttpJson.AuthStyle.BEARER)
            }
            ChatProtocol.ANTHROPIC -> {
                body.put("max_tokens", maxTokens).put("system", system).put("messages", JSONArray()
                    .put(JSONObject().put("role", "user").put("content", user)))
                ChatRequest(url, body, mapOf("anthropic-version" to ANTHROPIC_VERSION), HttpJson.AuthStyle.ANTHROPIC_API_KEY)
            }
        }
    }

    fun text(protocol: ChatProtocol, response: JSONObject): String {
        require(!response.has("error")) { "服务返回错误对象，未生成回复" }
        val text = when (protocol) {
            ChatProtocol.OPENAI -> {
                val choice = response.optJSONArray("choices")?.optJSONObject(0)
                    ?: throw IllegalArgumentException("OpenAI 响应缺少 choices[0]")
                val reason = choice.optString("finish_reason")
                require(reason !in setOf("length", "content_filter", "tool_calls", "function_call")) {
                    "模型输出未完成或被拒绝（${reason}），请检查模型/输出长度"
                }
                val message = choice.optJSONObject("message") ?: throw IllegalArgumentException("响应缺少 message")
                require(message.optString("refusal", "").isBlank()) { "模型拒绝了本次请求" }
                when (val content = message.opt("content")) {
                    is String -> content
                    is JSONArray -> textBlocks(content)
                    else -> ""
                }
            }
            ChatProtocol.ANTHROPIC -> {
                val reason = response.optString("stop_reason")
                require(reason in setOf("end_turn", "stop_sequence")) { "Anthropic 输出未完成或被拒绝（${reason}）" }
                textBlocks(response.optJSONArray("content") ?: throw IllegalArgumentException("Anthropic 响应缺少 content"))
            }
        }.trim()
        require(text.isNotEmpty()) { "接口未返回可用的文本内容" }
        return text
    }

    private fun textBlocks(blocks: JSONArray): String = (0 until blocks.length()).mapNotNull { i ->
        val block = blocks.optJSONObject(i) ?: return@mapNotNull null
        if (block.optString("type") == "text") block.optString("text") else null
    }.joinToString("\n")

    fun needsLegacyTokenLimit(error: ApiException): Boolean = error.status == 400 &&
        error.snippet.contains("max_completion_tokens", true) &&
        listOf("unsupported", "unknown", "unrecognized", "not supported", "extra", "not permitted")
            .any { error.snippet.contains(it, true) }
}

/** Shared text transport for judgment, ranking, replies, and connectivity tests. */
class ChatApi(private val config: ChatApiConfig, private val route: String) {
    fun complete(system: String, user: String, maxTokens: Int = 2048): String {
        val request = ChatWire.request(config, system, user, maxTokens)
        fun send(body: JSONObject) = HttpJson.post(request.url, config.key, body, route, request.headers, request.auth)
        val response = try { send(request.body) } catch (e: ApiException) {
            if (config.protocol != ChatProtocol.OPENAI || !ChatWire.needsLegacyTokenLimit(e)) throw e
            // Retry ONLY an explicitly unsupported optional parameter, not auth/rate-limit/server errors.
            val compatible = JSONObject(request.body.toString())
            compatible.remove("max_completion_tokens")
            compatible.put("max_tokens", maxTokens)
            send(compatible)
        }
        return try { ChatWire.text(config.protocol, response) }
            catch (e: IllegalArgumentException) { throw ApiException(route, null, e.message ?: "响应格式错误") }
    }
}

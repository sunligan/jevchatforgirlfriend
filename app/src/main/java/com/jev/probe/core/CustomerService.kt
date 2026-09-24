package com.jev.probe.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Fixed-reply customer-service beta: deterministic, no model generation. */
data class CustomerReplyPlan(
    val lines: List<String>,
    val reason: String,
    val needsHuman: Boolean = false
)

object CustomerReplyLogic {
    fun plan(messages: List<Msg>, first: String, second: String): CustomerReplyPlan {
        val a = first.trim(); val b = second.trim()
        if (a.isBlank() && b.isBlank()) return CustomerReplyPlan(emptyList(), "还没有配置固定话术", true)
        val mine = messages.filter { it.side == "me" }.map { it.text.trim() }.filter { it.isNotBlank() }
        val hasFirst = a.isNotBlank() && mine.any { it == a }
        val hasSecond = b.isNotBlank() && mine.any { it == b }
        return when {
            hasSecond -> CustomerReplyPlan(emptyList(), "两句固定话术都已出现，不重复回复")
            hasFirst && b.isNotBlank() -> CustomerReplyPlan(listOf(b), "检测到第一句已被自动回复，只补第二句")
            !hasFirst && a.isNotBlank() && b.isNotBlank() -> CustomerReplyPlan(listOf(a, b), "两句固定话术都未出现")
            !hasFirst && a.isNotBlank() -> CustomerReplyPlan(listOf(a), "只配置了第一句")
            b.isNotBlank() -> CustomerReplyPlan(listOf(b), "只配置了第二句")
            else -> CustomerReplyPlan(emptyList(), "无法形成固定回复", true)
        }
    }
}

data class CustomerLead(
    val id: String = UUID.randomUUID().toString(),
    val sourceApp: String,
    val sourceTitle: String,
    val category: String,
    val phone: String? = null,
    val wechat: String? = null,
    val rawText: String,
    val targetGroup: String,
    val createdAt: Long = System.currentTimeMillis(),
    val dispatched: Boolean = false
)

object CustomerLeadParser {
    private val phone = Regex("(?<!\\d)(?:1[3-9]\\d{9}|0\\d{2,3}[- ]?\\d{7,8})(?!\\d)")
    /** Shape of a WeChat ID: letter first, then 5..19 more of letter/digit/`_`/`-`. */
    private val idToken = Regex("(?i)(?<![a-z0-9_-])[a-z][a-z0-9_-]{5,19}(?![a-z0-9_-])")
    /** Bare 微信 covers 加微信 / 微信号 / 微信聊 without listing each. */
    private val hint = Regex("(?i)(微信|weixin|wechat|vx|v信|wx|联系方式|电话|手机号)")

    /**
     * A Latin token counts as a WeChat ID only when the same message asks for
     * contact details AND the token is shaped like an ID (carries a digit, `_`
     * or `-`). Shape alone is not enough: matching any 6..20 letter word turned
     * plain English ("please", "because") into phantom leads that beeped and
     * landed in the dispatch queue.
     */
    fun wechatId(text: String): String? {
        if (!hint.containsMatchIn(text)) return null
        return idToken.findAll(text)
            .map { it.value }
            .firstOrNull { token -> token.any { it.isDigit() || it == '_' || it == '-' } }
    }

    /** Asks for contact details but gives none — a human has to follow up. */
    fun isWeChatHint(text: String): Boolean =
        hint.containsMatchIn(text) && phone.find(text) == null && wechatId(text) == null

    fun extract(text: String, title: String, category: String, targetGroup: String): CustomerLead? {
        val raw = text.trim(); if (raw.isBlank()) return null
        val p = phone.find(raw)?.value?.replace("-", "")?.replace(" ", "")
        val w = wechatId(raw)
        if (p == null && w == null && !hint.containsMatchIn(raw)) return null
        return CustomerLead(
            sourceApp = "douyin", sourceTitle = title, category = category.ifBlank { "未分类" },
            phone = p, wechat = w, rawText = raw, targetGroup = targetGroup
        )
    }
}

class CustomerLeadStore(context: Context) {
    private val dir = File(context.filesDir, "customer")
    private val file get() = File(dir, "leads.json")
    private val lock = Any()

    fun append(lead: CustomerLead): Boolean = synchronized(lock) {
        val all = load().toMutableList()
        // Exact raw line from the same source/title within 24h is a duplicate event.
        val duplicate = all.any { it.sourceApp == lead.sourceApp && it.sourceTitle == lead.sourceTitle &&
            it.rawText == lead.rawText && System.currentTimeMillis() - it.createdAt < 86_400_000L }
        if (duplicate) return@synchronized false
        all.add(lead); write(all.takeLast(500)); true
    }

    fun all(): List<CustomerLead> = synchronized(lock) { load() }

    fun pending(targetGroup: String? = null): List<CustomerLead> = synchronized(lock) {
        load().filter { !it.dispatched && (targetGroup == null || it.targetGroup == targetGroup) }
    }

    fun markDispatched(id: String): Boolean = synchronized(lock) {
        val all = load(); val i = all.indexOfFirst { it.id == id }
        if (i < 0) return@synchronized false
        write(all.toMutableList().also { it[i] = it[i].copy(dispatched = true) }); true
    }

    /**
     * Delete every lead, returning how many there were.
     *
     * This is the only file in the app holding *other people's* phone numbers
     * and WeChat IDs, so it has to be wipeable from the UI. The settings
     * "清空知识库与历史" button only ever deleted `filesDir/kb`.
     */
    fun clear(): Int = synchronized(lock) {
        val n = load().size
        if (n == 0 && !file.exists()) return@synchronized 0
        if (file.exists() && !file.delete()) return@synchronized 0
        n
    }

    private fun load(): List<CustomerLead> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(file.readText(Charsets.UTF_8))
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                CustomerLead(
                    id = o.optString("id"), sourceApp = o.optString("sourceApp"), sourceTitle = o.optString("sourceTitle"),
                    category = o.optString("category"), phone = o.optString("phone").ifBlank { null },
                    wechat = o.optString("wechat").ifBlank { null }, rawText = o.optString("rawText"),
                    targetGroup = o.optString("targetGroup"), createdAt = o.optLong("createdAt"), dispatched = o.optBoolean("dispatched")
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun write(items: List<CustomerLead>) {
        dir.mkdirs(); val tmp = File(dir, "leads.json.tmp")
        val arr = JSONArray()
        items.forEach { l -> arr.put(JSONObject().apply {
            put("id", l.id); put("sourceApp", l.sourceApp); put("sourceTitle", l.sourceTitle)
            put("category", l.category); put("phone", l.phone ?: ""); put("wechat", l.wechat ?: "")
            put("rawText", l.rawText); put("targetGroup", l.targetGroup); put("createdAt", l.createdAt); put("dispatched", l.dispatched)
        }) }
        tmp.writeText(arr.toString(), Charsets.UTF_8)
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }
}

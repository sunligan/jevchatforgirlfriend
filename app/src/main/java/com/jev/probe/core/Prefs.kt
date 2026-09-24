package com.jev.probe.core

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.jev.probe.jev.ApiEndpoints
import com.jev.probe.jev.ChatApiConfig
import com.jev.probe.jev.ChatProtocol

/**
 * App-private config store. Holds the three API routes (judge / reply / vision),
 * the relationship description used in Jev's state, the conversation whitelist,
 * plus the context (D stage) and OCR (B stage) switches.
 *
 * Key handling: stored in app-private SharedPreferences (not world-readable,
 * never logged, never in code/git). Only key *lengths* are ever logged.
 */
class Prefs private constructor(private val sp: SharedPreferences, prefsName: String) {
    constructor(context: Context, prefsName: String = PREFS_MAIN) :
        this(context.getSharedPreferences(prefsName, Context.MODE_PRIVATE), prefsName)
    internal constructor(storage: SharedPreferences) : this(storage, "unit-test")

    /**
     * Only the real config migrates — and only the real config logs it. The
     * throwaway instances behind the settings test buttons and the KB self-check
     * have nothing to carry over, and used to print one migration line per tap.
     */
    init { if (prefsName == PREFS_MAIN) migrateIfNeeded() }

    /**
     * v1.2 -> v1.3: the single `openrouter_key` becomes the judge route's key.
     * `reply_model` keeps its old storage key, so it carries over untouched.
     */
    private fun migrateIfNeeded() {
        if (sp.getBoolean(K_MIGRATED_V13, false)) return   // runs exactly once
        val legacy = sp.getString(K_LEGACY_KEY, "") ?: ""
        val current = sp.getString(K_JUDGE_KEY, "") ?: ""
        val e = sp.edit().putBoolean(K_MIGRATED_V13, true)
        if (current.isBlank() && legacy.isNotBlank()) {
            e.putString(K_JUDGE_KEY, legacy)
            Log.i(TAG, "prefs migrated judgeKey.len=${legacy.length}")
        } else {
            Log.i(TAG, "prefs migrated judgeKey.len=${current.length} (no legacy key to copy)")
        }
        e.apply()
    }

    // ---------------------------------------------------------------- judge

    /** Legacy Jev provider: "openrouter" | "typesafe" | "custom". */
    var judgeProvider: String
        get() = sp.getString(K_JUDGE_PROVIDER, PROVIDER_OPENROUTER) ?: PROVIDER_OPENROUTER
        set(v) = sp.edit().putString(K_JUDGE_PROVIDER, v.trim()).apply()

    /** Host root; the path is appended per provider (see [judgeEndpoint]). */
    var judgeBaseUrl: String
        get() = sp.getString(K_JUDGE_BASE, when (judgeProtocol) {
            PROTOCOL_OPENAI -> DEFAULT_JUDGE_BASE_OPENAI
            PROTOCOL_ANTHROPIC -> DEFAULT_JUDGE_BASE_ANTHROPIC
            else -> DEFAULT_JUDGE_BASE_OPENROUTER
        }) ?: ""
        set(v) = sp.edit().putString(K_JUDGE_BASE, v.trim()).apply()

    var judgeKey: String
        get() = sp.getString(K_JUDGE_KEY, "") ?: ""
        set(v) = sp.edit().putString(K_JUDGE_KEY, v.trim()).apply()

    var judgeModel: String
        get() = sp.getString(K_JUDGE_MODEL, if (judgeProtocol == PROTOCOL_JEV) DEFAULT_JUDGE_MODEL_OPENROUTER else "") ?: ""
        set(v) = sp.edit().putString(K_JUDGE_MODEL, v.trim()).apply()

    /** "jev" | "openai" | "anthropic". Existing installs default to Jev. */
    var judgeProtocol: String
        get() = sp.getString(K_JUDGE_PROTOCOL, if (hasLegacyJudgeConfig()) PROTOCOL_JEV else PROTOCOL_OPENAI) ?: PROTOCOL_OPENAI
        set(v) = sp.edit().putString(K_JUDGE_PROTOCOL, v.trim()).apply()

    /** "openai" | "anthropic" for the reply generation route. */
    var replyProtocol: String
        get() = sp.getString(K_REPLY_PROTOCOL, PROTOCOL_OPENAI) ?: PROTOCOL_OPENAI
        set(v) = sp.edit().putString(K_REPLY_PROTOCOL, v.trim()).apply()

    /** Explicit opt-in reuse; upgrades with existing judgment settings keep their route. */
    var judgeUseReply: Boolean
        get() = sp.getBoolean(K_JUDGE_USE_REPLY, !hasLegacyJudgeConfig())
        set(v) = sp.edit().putBoolean(K_JUDGE_USE_REPLY, v).apply()

    private fun hasLegacyJudgeConfig(): Boolean = sp.contains(K_JUDGE_BASE) ||
        sp.contains(K_JUDGE_MODEL) || sp.contains(K_JUDGE_PROVIDER) || judgeKey.isNotBlank()

    /** Back-compat alias so older call sites keep compiling. */
    var openRouterKey: String
        get() = judgeKey
        set(v) { judgeKey = v }

    // ---------------------------------------------------------------- reply

    /** OpenAI-compatible base, up to and including `/v1`. */
    var replyBaseUrl: String
        get() = sp.getString(K_REPLY_BASE, DEFAULT_REPLY_BASE) ?: DEFAULT_REPLY_BASE
        set(v) = sp.edit().putString(K_REPLY_BASE, v.trim()).apply()

    /** Blank = fall back to [judgeKey]. */
    var replyKey: String
        get() = sp.getString(K_REPLY_KEY, "") ?: ""
        set(v) = sp.edit().putString(K_REPLY_KEY, v.trim()).apply()

    /** Generative model for drafting the 3 candidate replies. */
    var replyModel: String
        get() = sp.getString(K_REPLY_MODEL, DEFAULT_REPLY_MODEL) ?: DEFAULT_REPLY_MODEL
        set(v) = sp.edit().putString(K_REPLY_MODEL, v.trim()).apply()

    // --------------------------------------------------------------- vision

    /**
     * Blank = the OpenRouter vision default. Deliberately does NOT follow
     * [replyBaseUrl]: a reply host like DeepSeek has no vision endpoint, so
     * inheriting it would silently break OCR.
     */
    var visionBaseUrl: String
        get() = sp.getString(K_VISION_BASE, DEFAULT_VISION_BASE) ?: DEFAULT_VISION_BASE
        set(v) = sp.edit().putString(K_VISION_BASE, v.trim()).apply()

    /** Blank = fall back to [replyKey] then [judgeKey]. */
    var visionKey: String
        get() = sp.getString(K_VISION_KEY, "") ?: ""
        set(v) = sp.edit().putString(K_VISION_KEY, v.trim()).apply()

    var visionModel: String
        get() = sp.getString(K_VISION_MODEL, DEFAULT_VISION_MODEL) ?: DEFAULT_VISION_MODEL
        set(v) = sp.edit().putString(K_VISION_MODEL, v.trim()).apply()

    // -------------------------------------------------------- context (D)

    /**
     * Record per-contact history and inject it into analysis. Default OFF:
     * nothing about the user's chats is written to disk unless they opt in
     * (v1.3 revision, D stage).
     */
    var contextEnabled: Boolean
        get() = sp.getBoolean(K_CTX_ENABLED, false)
        set(v) = sp.edit().putBoolean(K_CTX_ENABLED, v).apply()

    /** How many recent history entries to inject. */
    var contextHistoryCount: Int
        get() = sp.getInt(K_CTX_COUNT, 30)
        set(v) = sp.edit().putInt(K_CTX_COUNT, v).apply()

    /** Auto-summarize a contact once enough history accumulates. */
    var autoSummary: Boolean
        get() = sp.getBoolean(K_AUTO_SUMMARY, true)
        set(v) = sp.edit().putBoolean(K_AUTO_SUMMARY, v).apply()

    // ------------------------------------------------------------ OCR (B)

    /** "mlkit" | "vision". */
    var ocrEngine: String
        get() = sp.getString(K_OCR_ENGINE, OCR_MLKIT) ?: OCR_MLKIT
        set(v) = sp.edit().putString(K_OCR_ENGINE, v.trim()).apply()

    /** Run generic OCR capture on apps with no dedicated adapter. */
    var ocrForUnknownApps: Boolean
        get() = sp.getBoolean(K_OCR_UNKNOWN, true)
        set(v) = sp.edit().putBoolean(K_OCR_UNKNOWN, v).apply()

    /** Fall back to OCR when an adapted app's node tree comes back empty. */
    var ocrFallback: Boolean
        get() = sp.getBoolean(K_OCR_FALLBACK, true)
        set(v) = sp.edit().putBoolean(K_OCR_FALLBACK, v).apply()

    /** Auto-analyze in OCR mode (default off: OCR costs a screenshot each time). */
    var ocrAutoAnalyze: Boolean
        get() = sp.getBoolean(K_OCR_AUTO, false)
        set(v) = sp.edit().putBoolean(K_OCR_AUTO, v).apply()

    // ------------------------------------------------------------- existing

    /** Free-text describing who the other person is; goes into Jev's state. */
    var relationship: String
        get() = sp.getString(K_REL, DEFAULT_REL) ?: DEFAULT_REL
        set(v) = sp.edit().putString(K_REL, v).apply()

    /** Master on/off for showing the overlay + running analysis. */
    var enabled: Boolean
        get() = sp.getBoolean(K_ENABLED, true)
        set(v) = sp.edit().putBoolean(K_ENABLED, v).apply()

    /** Returns an unsubscribe callback; listener is retained strongly until service teardown. */
    fun observeEnabled(onChange: () -> Unit): () -> Unit {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == K_ENABLED) onChange()
        }
        sp.registerOnSharedPreferenceChangeListener(listener)
        return { sp.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    /**
     * Conversation whitelist: titles the assistant is allowed to act on. Empty
     * set means "all conversations". Stored as a plain string set.
     */
    var whitelist: Set<String>
        get() = sp.getStringSet(K_WHITELIST, emptySet()) ?: emptySet()
        set(v) = sp.edit().putStringSet(K_WHITELIST, v).apply()

    /** Overlay panel opacity, 60..100 (%). Lower lets the chat show through. */
    var overlayOpacity: Int
        get() = sp.getInt(K_OPACITY, 92).coerceIn(60, 100)
        set(v) = sp.edit().putInt(K_OPACITY, v.coerceIn(60, 100)).apply()

    /** Remembered vertical position of the bubble (px); -1 = default. */
    var bubbleY: Int
        get() = sp.getInt(K_BUBBLE_Y, -1)
        set(v) = sp.edit().putInt(K_BUBBLE_Y, v).apply()

    /** Remembered horizontal position of the bubble (px); -1 = default. */
    var bubbleX: Int
        get() = sp.getInt(K_BUBBLE_X, -1)
        set(v) = sp.edit().putInt(K_BUBBLE_X, v).apply()

    // ------------------------------------------------------ customer service beta

    /** Customer beta runs deterministic fixed replies on the Douyin adapter. */
    var customerMode: Boolean
        get() = sp.getBoolean(K_CS_MODE, false)
        set(v) = sp.edit().putBoolean(K_CS_MODE, v).apply()

    /** When true, the beta may click a visible send button for fixed text only. */
    var customerAutoSend: Boolean
        get() = sp.getBoolean(K_CS_AUTO_SEND, false)
        set(v) = sp.edit().putBoolean(K_CS_AUTO_SEND, v).apply()

    var customerReplyFirst: String
        get() = sp.getString(K_CS_REPLY_FIRST, "您好，请问您需要咨询什么？") ?: ""
        set(v) = sp.edit().putString(K_CS_REPLY_FIRST, v).apply()

    var customerReplySecond: String
        get() = sp.getString(K_CS_REPLY_SECOND, "如果方便，请留下您的联系方式。") ?: ""
        set(v) = sp.edit().putString(K_CS_REPLY_SECOND, v).apply()

    var customerCategory: String
        get() = sp.getString(K_CS_CATEGORY, "未分类") ?: "未分类"
        set(v) = sp.edit().putString(K_CS_CATEGORY, v).apply()

    var customerWechatGroup: String
        get() = sp.getString(K_CS_GROUP, "") ?: ""
        set(v) = sp.edit().putString(K_CS_GROUP, v).apply()

    /** Auto-analyze on every incoming message; if false, user taps to analyze. */
    var autoAnalyze: Boolean
        get() = sp.getBoolean(K_AUTO, true)
        set(v) = sp.edit().putBoolean(K_AUTO, v).apply()

    // ------------------------------------------------------------- helpers

    /** Reply route key, falling back to the judge key. */
    fun effectiveReplyKey(): String = replyKey.ifBlank {
        if (ApiEndpoints.sameOrigin(replyBaseUrl, judgeBaseUrl)) judgeKey else ""
    }

    /** Vision route key, falling back to reply then judge. */
    fun effectiveVisionKey(): String = visionKey.ifBlank {
        when {
            ApiEndpoints.sameOrigin(visionBaseUrl, replyBaseUrl) -> effectiveReplyKey()
            ApiEndpoints.sameOrigin(visionBaseUrl, judgeBaseUrl) -> judgeKey
            else -> ""
        }
    }

    fun effectiveJudgeProtocol(): String = if (judgeUseReply) replyProtocol else judgeProtocol
    fun effectiveJudgeKey(): String = if (judgeUseReply) effectiveReplyKey() else judgeKey
    fun effectiveJudgeModel(): String = if (judgeUseReply) replyModel else judgeModel
    fun effectiveJudgeBase(): String = if (judgeUseReply) replyBaseUrl else judgeBaseUrl
    fun isJevJudge(): Boolean = effectiveJudgeProtocol() == PROTOCOL_JEV
    private fun chatProtocol(value: String): ChatProtocol = when (value) {
        PROTOCOL_OPENAI -> ChatProtocol.OPENAI
        PROTOCOL_ANTHROPIC -> ChatProtocol.ANTHROPIC
        else -> throw IllegalArgumentException("未知聊天协议：${value}")
    }
    fun judgeChatConfig() = ChatApiConfig(chatProtocol(effectiveJudgeProtocol()), effectiveJudgeBase(), effectiveJudgeKey(), effectiveJudgeModel())
    fun replyChatConfig() = ChatApiConfig(chatProtocol(replyProtocol), replyBaseUrl, effectiveReplyKey(), replyModel)

    /** Full POST URL for the selected judgment protocol. */
    fun judgeEndpoint(): String {
        val raw = effectiveJudgeBase().trim().trimEnd('/')
        return when (effectiveJudgeProtocol()) {
            PROTOCOL_OPENAI -> ApiEndpoints.chat(raw, ChatProtocol.OPENAI)
            PROTOCOL_ANTHROPIC -> ApiEndpoints.chat(raw, ChatProtocol.ANTHROPIC)
            else -> when (judgeProvider) {
                PROVIDER_TYPESAFE -> "$raw/v1/systemone"
                PROVIDER_CUSTOM -> judgeBaseUrl.trim()   // legacy Jev custom endpoint
                else -> "$raw/alpha/decisions"
            }
        }
    }

    /** Full POST URL for the selected reply protocol. */
    fun replyEndpoint(): String = ApiEndpoints.chat(replyBaseUrl, chatProtocol(replyProtocol))

    /** Same shape as [replyEndpoint]; blank falls back to the OpenRouter default. */
    fun visionEndpoint(): String {
        val base = visionBaseUrl.trim().ifBlank { DEFAULT_VISION_BASE }
        return "${base.trimEnd('/')}/chat/completions"
    }

    fun isAllowed(title: String?): Boolean {
        val wl = whitelist
        if (wl.isEmpty()) return true
        if (title == null) return false
        return wl.any { title.contains(it) }
    }

    /** Readiness gate: the judge route is the one that must be configured. */
    fun hasKey(): Boolean = effectiveJudgeKey().isNotBlank()

    companion object {
        private const val TAG = "JEVASSIST"

        /** The one real config file. Anything else is a scratch instance. */
        const val PREFS_MAIN = "jev_assistant"

        private const val K_LEGACY_KEY = "openrouter_key"
        private const val K_MIGRATED_V13 = "prefs_migrated_v13"
        private const val K_JUDGE_PROVIDER = "judge_provider"
        private const val K_JUDGE_BASE = "judge_base_url"
        private const val K_JUDGE_KEY = "judge_key"
        private const val K_JUDGE_MODEL = "judge_model"
        private const val K_JUDGE_PROTOCOL = "judge_protocol"
        private const val K_JUDGE_USE_REPLY = "judge_use_reply"
        private const val K_REPLY_BASE = "reply_base_url"
        private const val K_REPLY_PROTOCOL = "reply_protocol"
        private const val K_REPLY_KEY = "reply_key"
        private const val K_REPLY_MODEL = "reply_model"
        private const val K_VISION_BASE = "vision_base_url"
        private const val K_VISION_KEY = "vision_key"
        private const val K_VISION_MODEL = "vision_model"
        private const val K_CTX_ENABLED = "context_enabled"
        private const val K_CTX_COUNT = "context_history_count"
        private const val K_AUTO_SUMMARY = "auto_summary"
        private const val K_OCR_ENGINE = "ocr_engine"
        private const val K_OCR_UNKNOWN = "ocr_unknown_apps"
        private const val K_OCR_FALLBACK = "ocr_fallback"
        private const val K_OCR_AUTO = "ocr_auto_analyze"
        private const val K_REL = "relationship"
        private const val K_ENABLED = "enabled"
        private const val K_WHITELIST = "whitelist"
        private const val K_OPACITY = "overlay_opacity"
        private const val K_BUBBLE_Y = "bubble_y"
        private const val K_BUBBLE_X = "bubble_x"
        private const val K_AUTO = "auto_analyze"
        private const val K_CS_MODE = "customer_mode"
        private const val K_CS_AUTO_SEND = "customer_auto_send"
        private const val K_CS_REPLY_FIRST = "customer_reply_first"
        private const val K_CS_REPLY_SECOND = "customer_reply_second"
        private const val K_CS_CATEGORY = "customer_category"
        private const val K_CS_GROUP = "customer_wechat_group"

        const val PROVIDER_OPENROUTER = "openrouter"
        const val PROVIDER_TYPESAFE = "typesafe"
        const val PROVIDER_CUSTOM = "custom"

        const val PROTOCOL_JEV = "jev"
        const val PROTOCOL_OPENAI = "openai"
        const val PROTOCOL_ANTHROPIC = "anthropic"

        const val DEFAULT_JUDGE_BASE_OPENAI = "https://api.openai.com/v1"
        const val DEFAULT_JUDGE_MODEL_OPENAI = ""
        const val DEFAULT_JUDGE_BASE_ANTHROPIC = "https://api.anthropic.com"
        const val DEFAULT_JUDGE_MODEL_ANTHROPIC = ""
        const val DEFAULT_REPLY_BASE_ANTHROPIC = "https://api.anthropic.com"
        const val DEFAULT_REPLY_MODEL_ANTHROPIC = ""

        const val OCR_MLKIT = "mlkit"
        const val OCR_VISION = "vision"

        // Judge route presets.
        const val DEFAULT_JUDGE_BASE_OPENROUTER = "https://openrouter.ai/api"
        const val DEFAULT_JUDGE_MODEL_OPENROUTER = "typesafe/jev-1.13"
        const val DEFAULT_JUDGE_BASE_TYPESAFE = "https://api.typesafe.ai"
        const val DEFAULT_JUDGE_MODEL_TYPESAFE = "jev-latest"

        // Reply route presets (OpenAI-compatible chat completions).
        const val DEFAULT_REPLY_BASE = "https://openrouter.ai/api/v1"
        const val DEFAULT_REPLY_MODEL = "deepseek/deepseek-chat-v3.1"
        const val DEEPSEEK_BASE = "https://api.deepseek.com/v1"
        const val DEEPSEEK_MODEL = "deepseek-chat"
        const val DASHSCOPE_BASE = "https://dashscope.aliyuncs.com/compatible-mode/v1"
        const val DASHSCOPE_MODEL = "qwen-plus"

        // Vision route preset (OpenRouter region-available; user may change).
        const val DEFAULT_VISION_BASE = "https://openrouter.ai/api/v1"
        const val DEFAULT_VISION_MODEL = "qwen/qwen2.5-vl-72b-instruct"
        const val DASHSCOPE_VISION_MODEL = "qwen-vl-max"

        const val DEFAULT_REL = "对方是我的伴侣；from=me 的是我发的，from=other 的是对方发的"
    }
}

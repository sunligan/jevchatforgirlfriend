package com.jev.probe.core

import android.content.SharedPreferences
import com.jev.probe.jev.ChatProtocol
import org.junit.Assert.*
import org.junit.Test

/** Real Prefs routing on in-memory storage; no Android device or network required. */
class PrefsProtocolTest {
    private fun prefs() = Prefs(MemoryPreferences())

    @Test fun freshInstallDefaultsToReusableOrdinaryModelNotJev() {
        val p = prefs()
        assertEquals(Prefs.PROTOCOL_OPENAI, p.judgeProtocol)
        assertTrue(p.judgeUseReply)
        assertFalse(p.isJevJudge())
    }
    @Test fun legacySavedJevConfigurationRemainsJevAfterUpgrade() {
        val store = MemoryPreferences()
        store.edit().putString("judge_key", "old-test-key")
            .putString("judge_base_url", "https://openrouter.ai/api")
            .putString("judge_model", "typesafe/jev-1.13").apply()
        val p = Prefs(store)
        assertFalse(p.judgeUseReply)
        assertTrue(p.isJevJudge())
        assertEquals("https://openrouter.ai/api/alpha/decisions", p.judgeEndpoint())
    }
    @Test fun anthropicReuseReallySelectsReplyModelKeyAndEndpoint() {
        val p = prefs()
        p.judgeUseReply = true
        p.judgeProtocol = Prefs.PROTOCOL_JEV
        p.replyProtocol = Prefs.PROTOCOL_ANTHROPIC
        p.replyBaseUrl = "https://api.anthropic.com/v1"
        p.replyKey = "anthropic-test-key"
        p.replyModel = "user-selected-model"
        assertTrue(p.hasKey())
        assertFalse(p.isJevJudge())
        assertEquals("https://api.anthropic.com/v1/messages", p.judgeEndpoint())
        assertEquals("anthropic-test-key", p.effectiveJudgeKey())
        assertEquals("user-selected-model", p.effectiveJudgeModel())
        assertEquals(ChatProtocol.ANTHROPIC, p.judgeChatConfig().protocol)
    }
    @Test fun independentJudgeAndReplyCanUseDifferentProtocols() {
        val p = prefs()
        p.judgeUseReply = false
        p.judgeProtocol = Prefs.PROTOCOL_OPENAI
        p.judgeBaseUrl = "https://api.openai.com/v1/chat/completions"
        p.judgeKey = "judge-test-key"
        p.judgeModel = "judge-id"
        p.replyProtocol = Prefs.PROTOCOL_ANTHROPIC
        p.replyBaseUrl = "https://api.anthropic.com"
        p.replyKey = "reply-test-key"
        p.replyModel = "reply-id"
        assertEquals(ChatProtocol.OPENAI, p.judgeChatConfig().protocol)
        assertEquals(ChatProtocol.ANTHROPIC, p.replyChatConfig().protocol)
        assertEquals("judge-test-key", p.effectiveJudgeKey())
        assertEquals("reply-test-key", p.effectiveReplyKey())
    }
    @Test fun blankKeyNeverLeaksToDifferentProviderOrVisionHost() {
        val p = prefs()
        p.judgeKey = "private-test-key"
        p.judgeBaseUrl = "https://api.anthropic.com"
        p.replyBaseUrl = "https://api.openai.com/v1"
        p.visionBaseUrl = "https://third-party.example/v1"
        p.replyKey = ""
        p.visionKey = ""
        assertEquals("", p.effectiveReplyKey())
        assertEquals("", p.effectiveVisionKey())
    }
    @Test fun existingSameOriginKeyFallbackStillWorks() {
        val p = prefs()
        p.judgeKey = "shared-test-key"
        p.judgeBaseUrl = "https://openrouter.ai/api"
        p.replyBaseUrl = "https://openrouter.ai/api/v1"
        p.replyKey = ""
        assertEquals("shared-test-key", p.effectiveReplyKey())
    }
    @Test fun allLegacyJevEndpointStylesArePreserved() {
        val p = prefs()
        p.judgeUseReply = false; p.judgeProtocol = Prefs.PROTOCOL_JEV
        p.judgeProvider = Prefs.PROVIDER_TYPESAFE; p.judgeBaseUrl = "https://api.typesafe.ai"
        assertEquals("https://api.typesafe.ai/v1/systemone", p.judgeEndpoint())
        p.judgeProvider = Prefs.PROVIDER_CUSTOM; p.judgeBaseUrl = "https://custom.example/decide"
        assertEquals("https://custom.example/decide", p.judgeEndpoint())
    }
}

private class MemoryPreferences : SharedPreferences {
    private val values = mutableMapOf<String, Any?>()
    override fun getAll(): MutableMap<String, *> = values.toMutableMap()
    override fun getString(key: String?, defValue: String?): String? = values[key] as? String ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = (values[key] as? Set<String>)?.toMutableSet() ?: defValues
    override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue
    override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float = values[key] as? Float ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
    override fun contains(key: String?): Boolean = values.containsKey(key)
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val changes = mutableMapOf<String, Any?>()
        private var clear = false
        private fun put(key: String?, value: Any?): SharedPreferences.Editor { changes[requireNotNull(key)] = value; return this }
        override fun putString(key: String?, value: String?) = put(key, value)
        override fun putStringSet(key: String?, values: MutableSet<String>?) = put(key, values?.toSet())
        override fun putInt(key: String?, value: Int) = put(key, value)
        override fun putLong(key: String?, value: Long) = put(key, value)
        override fun putFloat(key: String?, value: Float) = put(key, value)
        override fun putBoolean(key: String?, value: Boolean) = put(key, value)
        override fun remove(key: String?) = put(key, null)
        override fun clear(): SharedPreferences.Editor { clear = true; return this }
        override fun commit(): Boolean { apply(); return true }
        override fun apply() {
            if (clear) values.clear()
            changes.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
        }
    }
}

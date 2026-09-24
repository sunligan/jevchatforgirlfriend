package com.jev.probe.jev

import com.jev.probe.core.*
import org.junit.Assert.*
import org.junit.Test

class JevStateTest {
    @Test fun captureWarningIsSentToJudgeNotOnlyShownInUi() {
        val snapshot = ChatSnapshot(null, listOf(Msg("other", "测试")), note = "说话人未确认", source = CaptureSource.SCREEN_OCR)
        assertEquals("说话人未确认", JevQuestions.buildState(snapshot, "测试关系").getString("capture_warning"))
    }
    @Test fun ordinaryStateDoesNotInventBackgroundOrHistory() {
        val snapshot = ChatSnapshot("甲", listOf(Msg("other", "你好")))
        val state = JevQuestions.buildState(snapshot, "朋友")
        assertFalse(state.has("history"))
        assertFalse(state.has("background"))
        assertFalse(state.has("capture_warning"))
        assertEquals("other", state.getJSONObject("chat").getString("latest_from"))
    }
}

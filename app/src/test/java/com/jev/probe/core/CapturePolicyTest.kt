package com.jev.probe.core

import org.junit.Assert.*
import org.junit.Test

class CapturePolicyTest {
    @Test fun keyboardLayoutDoesNotDisableGenuineNewTextAnalysis() {
        val p = CapturePolicy(); p.onLayout(1000)
        assertTrue(p.allowAuto(1100))
        assertFalse(p.allowOcr(1100, sameConversation = true))
    }
    @Test fun knownScrollSuppressesAutomaticOcrAndAnalysisBurst() {
        val p = CapturePolicy(); p.onScroll(1000)
        assertFalse(p.allowAuto(1100))
        assertFalse(p.allowOcr(1100, true))
        assertTrue(p.allowAuto(1700))
        assertTrue(p.allowOcr(1700, true))
    }
    @Test fun changingConversationIsNotMistakenForOldKeyboardLayout() {
        val p = CapturePolicy(); p.onLayout(1000)
        assertTrue(p.allowOcr(1100, false))
    }
    @Test fun pauseBlocksEveryCapturePolicyAndResumeClearsOldBurst() {
        val p = CapturePolicy(); p.onScroll(1000)
        assertTrue(p.setPaused(true))
        assertFalse(p.allowAuto(5000))
        assertFalse(p.allowOcr(5000, false))
        assertFalse(p.allowOcr(5000, true))
        assertFalse(p.setPaused(true))
        assertTrue(p.setPaused(false))
        assertTrue(p.allowAuto(5001))
        assertTrue(p.allowOcr(5001, true))
    }
    @Test fun pauseResumeCannotReviveOldReplyTicket() {
        val p = CapturePolicy(); val guard = ConversationGuard()
        val owner = ConversationGuard.Observation("wechat", 7, "甲", "A")
        guard.observe(owner); val old = guard.start()!!
        if (p.setPaused(true)) guard.invalidate()
        assertFalse(guard.accepts(old))
        if (p.setPaused(false)) guard.invalidate()
        guard.observe(owner)
        assertFalse(guard.canFill(old, owner))
        assertTrue(guard.accepts(guard.start()!!))
    }
}

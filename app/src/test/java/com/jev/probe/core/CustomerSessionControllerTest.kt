package com.jev.probe.core

import org.junit.Assert.*
import org.junit.Test

class CustomerSessionControllerTest {
    private val cfg = CustomerConfig("您好", "请留下联系方式", "高意向", "客户群", autoSend = false)

    private fun snap(vararg msgs: Pair<String, String>) =
        ChatSnapshot("客户A", msgs.map { Msg(it.first, it.second) })

    @Test fun firstSnapshotPlansBothLines() {
        val a = CustomerSessionController().onSnapshot(snap("other" to "你好"), cfg)
        assertEquals(listOf("您好", "请留下联系方式"), a.plan.lines)
        assertFalse(a.autoSend)
    }

    /** The first cut read only the last incoming message, so a number followed
     *  by any other line was lost. */
    @Test fun phoneInTheFirstOfTwoNewMessagesIsStillCaught() {
        val a = CustomerSessionController().onSnapshot(
            snap("other" to "13800138000", "other" to "在吗"), cfg)
        assertEquals(1, a.leads.size)
        assertEquals("13800138000", a.leads[0].phone)
        assertEquals("客户A", a.leads[0].sourceTitle)
    }

    @Test fun appendedMessageIsScannedOnceOnly() {
        val c = CustomerSessionController()
        c.onSnapshot(snap("other" to "13800138000"), cfg)
        val second = c.onSnapshot(
            snap("other" to "13800138000", "me" to "您好", "other" to "在吗"), cfg)
        assertTrue(second.leads.isEmpty())
        assertNull(second.notify)
    }

    @Test fun unchangedScreenProducesNothing() {
        val c = CustomerSessionController()
        val s = snap("other" to "13800138000")
        assertEquals(1, c.onSnapshot(s, cfg).leads.size)
        assertTrue(c.onSnapshot(s, cfg).leads.isEmpty())
    }

    @Test fun resetMakesTheNextConversationScanFresh() {
        val c = CustomerSessionController()
        c.onSnapshot(snap("other" to "13800138000"), cfg)
        c.reset()
        val a = c.onSnapshot(ChatSnapshot("客户B", listOf(Msg("other", "13800138000"))), cfg)
        assertEquals(1, a.leads.size)
        assertEquals("客户B", a.leads[0].sourceTitle)
    }

    @Test fun autoSendOnlyWhenConfiguredAndAScriptExists() {
        val c = CustomerSessionController()
        assertFalse(c.onSnapshot(snap("other" to "你好"), cfg).autoSend)
        assertTrue(c.onSnapshot(snap("other" to "你好"), cfg.copy(autoSend = true)).autoSend)
        val blank = c.onSnapshot(
            snap("other" to "你好"), cfg.copy(autoSend = true, replyFirst = "", replySecond = ""))
        assertFalse(blank.autoSend)
        assertTrue(blank.plan.needsHuman)
    }

    @Test fun autoSendStopsOnceBothLinesWereSent() {
        val a = CustomerSessionController().onSnapshot(
            snap("me" to "您好", "me" to "请留下联系方式", "other" to "好的"),
            cfg.copy(autoSend = true))
        assertTrue(a.plan.lines.isEmpty())
        assertFalse(a.autoSend)
    }

    @Test fun severalLeadsInOneScanShareOneNotification() {
        val a = CustomerSessionController().onSnapshot(
            snap("other" to "13800138000", "other" to "电话 13900139000"), cfg)
        assertEquals(2, a.leads.size)
        assertTrue(a.notify!!.contains("2 条"))
    }

    @Test fun hintOnlyLeadsAreReportedAsNeedingAHuman() {
        val a = CustomerSessionController().onSnapshot(snap("other" to "方便加微信吗"), cfg)
        assertEquals(1, a.leads.size)
        assertNull(a.leads[0].phone)
        assertTrue(a.notify!!.contains("人工处理"))
    }

    @Test fun scanDepthIsCapped() {
        val msgs = (1..30).map { Msg("other", "1380013${it.toString().padStart(4, '0')}") }
        val a = CustomerSessionController().onSnapshot(ChatSnapshot("客户A", msgs), cfg)
        assertEquals(CustomerSessionController.MAX_SCAN, a.leads.size)
    }

    @Test fun sendScheduleCoversTheFillVerifyChain() {
        assertEquals(emptyList<Long>(), CustomerSendSchedule.delays(0))
        assertEquals(listOf(0L, 900L), CustomerSendSchedule.delays(2))
        // fillInput's worst path is SET_TEXT → FOCUS → PASTE → verify at 3 × 150 ms.
        assertTrue(CustomerSendSchedule.INTERVAL_MS > 450L)
    }

    // ---- the dispatch latch. Douyin decides "mine" by centre-of-screen, so the
    // ---- window before a sent bubble renders looks exactly like "not sent yet".
    @Test fun autoSendFiresOncePerPlanPerConversation() {
        val c = CustomerSessionController()
        val auto = cfg.copy(autoSend = true)
        val s = snap("other" to "你好")
        assertTrue(c.onSnapshot(s, auto).dispatchNow)
        assertFalse(c.onSnapshot(s, auto).dispatchNow)
        assertFalse(c.onSnapshot(s, auto).dispatchNow)
        // The panel keeps offering the button: a human press is never latched.
        assertTrue(c.onSnapshot(s, auto).autoSend)
    }

    @Test fun aChangedPlanMayDispatchAgain() {
        val c = CustomerSessionController()
        val auto = cfg.copy(autoSend = true)
        assertTrue(c.onSnapshot(snap("other" to "你好"), auto).dispatchNow)
        // Line one is now on screen as sent, so the plan shrinks to line two.
        val second = c.onSnapshot(
            snap("other" to "你好", "me" to "您好", "other" to "在吗"), auto)
        assertEquals(listOf("请留下联系方式"), second.plan.lines)
        assertTrue(second.dispatchNow)
        assertFalse(c.onSnapshot(
            snap("other" to "你好", "me" to "您好", "other" to "在吗"), auto).dispatchNow)
    }

    @Test fun resetClearsTheDispatchLatch() {
        val c = CustomerSessionController()
        val auto = cfg.copy(autoSend = true)
        assertTrue(c.onSnapshot(snap("other" to "你好"), auto).dispatchNow)
        c.reset()
        assertTrue(c.onSnapshot(snap("other" to "你好"), auto).dispatchNow)
    }

    @Test fun manualModeNeverDispatches() {
        val a = CustomerSessionController().onSnapshot(snap("other" to "你好"), cfg)
        assertFalse(a.autoSend)
        assertFalse(a.dispatchNow)
    }

    @Test fun unconfiguredScriptNeverDispatches() {
        val a = CustomerSessionController().onSnapshot(
            snap("other" to "你好"), cfg.copy(autoSend = true, replyFirst = "", replySecond = ""))
        assertFalse(a.dispatchNow)
        assertTrue(a.plan.needsHuman)
    }
}

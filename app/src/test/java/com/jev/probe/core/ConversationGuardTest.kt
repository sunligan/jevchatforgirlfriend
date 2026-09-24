package com.jev.probe.core

import org.junit.Assert.*
import org.junit.Test

class ConversationGuardTest {
    private val a = ConversationGuard.Observation("com.tencent.mm", 7, "甲", "message-1")
    private fun started(): Pair<ConversationGuard, ConversationGuard.Ticket> {
        val guard = ConversationGuard()
        guard.observe(a)
        return guard to guard.start()!!
    }

    @Test fun unchangedScreenDoesNotInvalidateRequest() {
        val (guard, ticket) = started()
        assertFalse(guard.observe(a))
        assertTrue(guard.accepts(ticket))
        assertTrue(guard.canFill(ticket, a))
    }
    @Test fun sameMessagesDifferentPersonInvalidateRequest() {
        val (guard, ticket) = started()
        guard.observe(a.copy(title = "乙"))
        assertFalse(guard.accepts(ticket))
    }
    @Test fun newMessageInvalidatesOldResult() {
        val (guard, ticket) = started()
        guard.observe(a.copy(signature = "message-2"))
        assertFalse(guard.accepts(ticket))
        assertNotNull(guard.start()) // new message is not dropped behind a busy flag
    }
    @Test fun changingAppOrWindowPreventsFillEvenBeforeCaptureEvent() {
        val (guard, ticket) = started()
        assertFalse(guard.canFill(ticket, a.copy(packageName = "com.tencent.mobileqq")))
        assertFalse(guard.canFill(ticket, a.copy(windowId = 8)))
        assertFalse(guard.canFill(ticket, a.copy(title = "乙")))
        assertFalse(guard.canFill(ticket, a.copy(signature = "message-2")))
    }
    @Test fun leavingAndReturningToSameChatDoesNotReviveOldResult() {
        val (guard, ticket) = started()
        guard.invalidate()
        guard.observe(a)
        assertFalse(guard.accepts(ticket))
        assertFalse(guard.canFill(ticket, a))
        assertTrue(guard.accepts(guard.start()!!))
    }
    @Test fun secondRequestOnSameScreenSupersedesFirst() {
        val (guard, first) = started()
        val second = guard.start()!!
        assertFalse(guard.accepts(first))
        assertTrue(guard.accepts(second))
    }
    @Test fun shutdownOrDisabledStateRejectsEveryPendingTicket() {
        val (guard, ticket) = started()
        guard.invalidate()
        assertFalse(guard.accepts(ticket))
        assertNull(guard.start())
    }
    @Test fun missingIdentityAndOcrAreCopyOnly() {
        for (screen in listOf(a.copy(title = null), a.copy(title = ""), a.copy(title = "  "),
            a.copy(packageName = ""), a.copy(allowsFill = false))) {
            val guard = ConversationGuard()
            guard.observe(screen)
            assertFalse(guard.canFill(guard.start()!!, screen))
        }
    }
    @Test fun ocrCannotBecomeFillableThroughLiveTreeReplacement() {
        val guard = ConversationGuard()
        guard.observe(a.copy(allowsFill = false))
        assertFalse(guard.canFill(guard.start()!!, a))
    }
}

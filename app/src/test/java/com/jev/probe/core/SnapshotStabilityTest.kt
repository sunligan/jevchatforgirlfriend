package com.jev.probe.core

import org.junit.Assert.*
import org.junit.Test

class SnapshotStabilityTest {
    private fun s(vararg values: String) = ChatSnapshot("甲", values.map { Msg("other", it) })

    @Test fun sameVisibleMessagesAreSame() {
        val old = s("A", "B")
        assertEquals(SnapshotChange.SAME, SnapshotStability.classify(old, old.copy()))
    }
    @Test fun keyboardRemovingOlderVisibleMessagesPreservesLastMessages() {
        assertEquals(SnapshotChange.VIEWPORT_ONLY, SnapshotStability.classify(s("A", "B", "C", "D"), s("C", "D")))
    }
    @Test fun keyboardClosingAndRevealingOlderMessagesDoesNotRerun() {
        assertEquals(SnapshotChange.VIEWPORT_ONLY, SnapshotStability.classify(s("C", "D"), s("A", "B", "C", "D")))
    }
    @Test fun scrollToOlderMessagesIsViewportOnly() {
        assertEquals(SnapshotChange.VIEWPORT_ONLY, SnapshotStability.classify(s("A", "B", "C"), s("A", "B")))
    }
    @Test fun appendedIncomingMessageIsChangedEvenIfKeyboardHidesOldTop() {
        val old = s("A", "B", "C")
        val now = s("B", "C", "D")
        assertEquals(SnapshotChange.CONTENT_CHANGED, SnapshotStability.classify(old, now))
        assertTrue(SnapshotStability.isAppend(old, now))
    }
    @Test fun exactRepeatedAppendedMessageIsStillNew() {
        assertEquals(SnapshotChange.CONTENT_CHANGED, SnapshotStability.classify(s("好的"), s("好的", "好的")))
        assertTrue(SnapshotStability.isAppend(s("好的"), s("好的", "好的")))
    }
    @Test fun editingAnEarlierMessageCannotPassBySharingTheLastLine() {
        assertEquals(SnapshotChange.CONTENT_CHANGED, SnapshotStability.classify(s("旧约定", "好的"), s("新约定", "好的")))
    }
    @Test fun messageDeletionInsideTheSequenceIsNotAViewportSlice() {
        assertEquals(SnapshotChange.CONTENT_CHANGED, SnapshotStability.classify(s("A", "B", "C"), s("A", "C")))
    }
    @Test fun emptyTreeDoesNotBorrowKnownConversation() {
        assertEquals(SnapshotChange.CONTENT_CHANGED, SnapshotStability.classify(s("A"), s()))
        assertEquals(SnapshotChange.CONTENT_CHANGED, SnapshotStability.classify(s(), s("A")))
    }
    @Test fun otherTitleOrSourceDoesNotBorrowAnyMessage() {
        val old = s("A", "B")
        assertEquals(SnapshotChange.CONTENT_CHANGED, SnapshotStability.classify(old, s("B").copy(title = "乙")))
        assertEquals(SnapshotChange.CONTENT_CHANGED, SnapshotStability.classify(old, s("B").copy(source = CaptureSource.SCREEN_OCR)))
    }
    @Test fun missingIdentityDoesNotBorrowPreviousContext() {
        assertEquals(SnapshotChange.CONTENT_CHANGED, SnapshotStability.classify(s("A", "B").copy(title = null), s("B").copy(title = null)))
    }
    @Test fun changingSenderIsChanged() {
        val old = s("A", "B")
        val now = old.copy(messages = listOf(Msg("other", "A"), Msg("me", "B")))
        assertEquals(SnapshotChange.CONTENT_CHANGED, SnapshotStability.classify(old, now))
    }
    @Test fun viewportKeepsTicketButNewMessageRevokesIt() {
        val guard = ConversationGuard()
        val old = s("A", "B", "C")
        fun observe(snapshot: ChatSnapshot) = ConversationGuard.Observation("com.tencent.mm", 7, snapshot.title, snapshot.signature())
        guard.observe(observe(old))
        val ticket = guard.start()!!
        val resized = SnapshotStability.canonical(old, s("B", "C"))
        assertFalse(guard.observe(observe(resized)))
        assertTrue(guard.canFill(ticket, observe(resized)))
        val newer = SnapshotStability.canonical(old, s("B", "C", "D"))
        assertTrue(guard.observe(observe(newer)))
        assertFalse(guard.accepts(ticket))
    }
    @Test fun switchingAppOrWindowStillRejectsAnIdenticalTail() {
        val guard = ConversationGuard()
        val initial = ConversationGuard.Observation("com.tencent.mm", 7, "甲", s("B").signature())
        guard.observe(initial)
        val ticket = guard.start()!!
        assertFalse(guard.canFill(ticket, initial.copy(packageName = "com.twitter.android")))
        assertFalse(guard.canFill(ticket, initial.copy(windowId = 8)))
        assertFalse(guard.canFill(ticket, initial.copy(title = "乙")))
    }
    @Test fun revealingOlderHistoryIsNotAnAppend() {
        assertFalse(SnapshotStability.isAppend(s("C", "D"), s("A", "B", "C", "D")))
        assertFalse(SnapshotStability.isAppend(null, s("A")))
    }
}

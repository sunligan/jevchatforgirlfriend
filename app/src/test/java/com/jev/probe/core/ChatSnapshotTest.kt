package com.jev.probe.core

import org.junit.Assert.*
import org.junit.Test

class ChatSnapshotTest {
    @Test fun signatureCoversEntireTenMessageModelWindow() {
        val messages = (1..10).map { Msg("other", "message-${it}") }
        val a = ChatSnapshot("甲", messages)
        val b = a.copy(messages = listOf(Msg("other", "changed")) + messages.drop(1))
        assertNotEquals(a.signature(), b.signature())
    }
    @Test fun textDelimitersDoNotCreateSignatureCollisions() {
        val a = ChatSnapshot("甲", listOf(Msg("other", "x|me:y")))
        val b = ChatSnapshot("甲", listOf(Msg("other", "x"), Msg("me", "y")))
        assertNotEquals(a.signature(), b.signature())
    }
    @Test fun changingSenderChangesSignature() {
        val a = ChatSnapshot("甲", listOf(Msg("other", "好的")))
        assertNotEquals(a.signature(), a.copy(messages = listOf(Msg("me", "好的"))).signature())
    }
}

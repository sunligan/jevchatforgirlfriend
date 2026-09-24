package com.jev.probe.core

import org.junit.Assert.*
import org.junit.Test

class MoneyGuardTest {
    @Test fun paymentAndGiftChromeIsRefused() {
        assertTrue(MoneyGuard.mentions("确认支付", null))
        assertTrue(MoneyGuard.mentions(null, "发送红包"))
        assertTrue(MoneyGuard.mentions("转账", null))
        assertTrue(MoneyGuard.mentions("收款", null))
        assertTrue(MoneyGuard.mentions(null, "送礼物"))
        assertTrue(MoneyGuard.mentions("打赏", null))
        assertTrue(MoneyGuard.mentions(null, "DOU+充值"))
        assertTrue(MoneyGuard.mentions("立即结算", null))
        assertTrue(MoneyGuard.mentions("Checkout", null))
        assertTrue(MoneyGuard.mentions(null, "Pay now"))
    }

    @Test fun plainComposerChromeIsAllowed() {
        assertFalse(MoneyGuard.mentions("发送", null))
        assertFalse(MoneyGuard.mentions(null, "Send"))
        assertFalse(MoneyGuard.mentions(null, "更多功能"))
        assertFalse(MoneyGuard.mentions(null, "表情"))
        assertFalse(MoneyGuard.mentions("", ""))
        // Layout containers on the ancestor chain carry no text at all.
        assertFalse(MoneyGuard.mentions(null, null))
    }

    @Test fun matchingIsCaseInsensitive() {
        assertTrue(MoneyGuard.mentions("PAY", null))
        assertTrue(MoneyGuard.mentions(null, "Wallet"))
        assertTrue(MoneyGuard.mentions(null, "TRANSFER"))
    }

    /** A chat that merely mentions money must not disable sending — the guard is
     *  scoped to the button's subtree and ancestors, and this is the text half
     *  of that contract. */
    @Test fun messageTextIsNotWhatTheGuardSees() {
        // The caller only ever passes a candidate button's own text/desc.
        assertFalse(MoneyGuard.mentions("发送", "发送"))
    }
}

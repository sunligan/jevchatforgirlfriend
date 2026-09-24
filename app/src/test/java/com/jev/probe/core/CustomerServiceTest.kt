package com.jev.probe.core

import org.junit.Assert.*
import org.junit.Test

class CustomerServiceTest {
    @Test fun bothFixedLinesArePlannedWhenNothingWasSent() {
        val p = CustomerReplyLogic.plan(listOf(Msg("other", "你好")), "您好", "请留下电话")
        assertEquals(listOf("您好", "请留下电话"), p.lines)
    }
    @Test fun firstAutoReplyMeansOnlySecondLineIsPlanned() {
        val p = CustomerReplyLogic.plan(listOf(Msg("me", "您好"), Msg("other", "电话给你")), "您好", "请留下电话")
        assertEquals(listOf("请留下电话"), p.lines)
    }
    @Test fun completedReplyDoesNotRepeat() {
        val p = CustomerReplyLogic.plan(listOf(Msg("me", "您好"), Msg("me", "请留下电话")), "您好", "请留下电话")
        assertTrue(p.lines.isEmpty())
    }
    @Test fun leadParserExtractsPhoneAndWechat() {
        val p = CustomerLeadParser.extract("我的电话是 13800138000，微信 abc_12345", "高意向", "客户群")!!
        assertEquals("13800138000", p.phone)
        assertEquals("abc_12345", p.wechat)
        assertEquals("高意向", p.category)
        assertEquals("客户群", p.targetGroup)
    }
    @Test fun hintWithoutConcreteContactNeedsHuman() {
        val p = CustomerLeadParser.extract("方便加微信聊吗？", "未分类", "客户群")!!
        assertNull(p.phone); assertNull(p.wechat)
        assertTrue(CustomerLeadParser.isWeChatHint(p.rawText))
    }
    @Test fun randomWordsAreNotSavedAsWechat() {
        assertNull(CustomerLeadParser.extract("hello thanks", "", ""))
    }
}

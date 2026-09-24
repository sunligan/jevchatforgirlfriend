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
    /** The shipped default used to be two non-blank lines, so auto-send had text
     *  to fire at a stranger before anyone had configured a script. */
    @Test fun unconfiguredScriptNeedsAHuman() {
        val p = CustomerReplyLogic.plan(listOf(Msg("other", "你好")), "", "")
        assertTrue(p.lines.isEmpty())
        assertTrue(p.needsHuman)
    }

    @Test fun leadParserExtractsPhoneAndWechat() {
        val p = CustomerLeadParser.extract(
            "我的电话是 13800138000，微信 abc_12345", "客户A", "高意向", "客户群")!!
        assertEquals("13800138000", p.phone)
        assertEquals("abc_12345", p.wechat)
        assertEquals("客户A", p.sourceTitle)
        assertEquals("高意向", p.category)
        assertEquals("客户群", p.targetGroup)
    }
    @Test fun wechatIdAfterAnExplicitLabel() {
        assertEquals("abc_12345", CustomerLeadParser.extract("微信 abc_12345", "", "", "")!!.wechat)
        assertEquals("test_2024", CustomerLeadParser.extract("vx：test_2024", "", "", "")!!.wechat)
        assertEquals("abc123456", CustomerLeadParser.extract("微信号 abc123456", "", "", "")!!.wechat)
    }
    @Test fun hintWithoutConcreteContactNeedsHuman() {
        val p = CustomerLeadParser.extract("方便加微信聊吗？", "", "未分类", "客户群")!!
        assertNull(p.phone)
        assertNull(p.wechat)
        assertTrue(CustomerLeadParser.isWeChatHint(p.rawText))
    }
    @Test fun landlineIsAPhoneLead() {
        assertEquals("02112345678", CustomerLeadParser.extract("电话 021-12345678", "", "", "")!!.phone)
    }

    // ---- false positives. A Latin token on its own is not a WeChat ID; the
    // ---- first cut matched any 6..20 letter word and only special-cased six.
    @Test fun plainEnglishIsNotALead() {
        assertNull(CustomerLeadParser.extract("please help me because of the weather", "", "", ""))
        assertNull(CustomerLeadParser.extract("hello thanks", "", "", ""))
    }
    @Test fun productNamesAreNotALead() {
        assertNull(CustomerLeadParser.extract("iPhone15 好用吗", "", "", ""))
    }
    @Test fun plainChineseWithoutContactIntentIsNotALead() {
        assertNull(CustomerLeadParser.extract("这个多少钱", "", "", ""))
        assertNull(CustomerLeadParser.extract("好的，我知道了", "", "", ""))
    }
    @Test fun idShapedTokenWithoutContactIntentIsNotALead() {
        assertNull(CustomerLeadParser.extract("订单号 abc_12345 已经处理", "", "", ""))
    }
    @Test fun pureLatinWordNextToAHintIsNotALead() {
        // The hint is there, but "please" has no digit/_/- so it is not an ID.
        val p = CustomerLeadParser.extract("加微信 please", "", "", "")!!
        assertNull(p.wechat)
        assertTrue(CustomerLeadParser.isWeChatHint(p.rawText))
    }
}

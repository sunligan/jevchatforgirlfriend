package com.jev.probe.jev

import org.junit.Assert.*
import org.junit.Test

class ReplyParserTest {
    @Test fun validThreeReplies() {
        assertEquals(listOf("我先看一下", "我查完再回你", "你指的是哪件事呀"),
            ReplyParser.parse("[\"我先看一下\",\"我查完再回你\",\"你指的是哪件事呀\"]"))
    }
    @Test fun supportsJsonCodeFence() {
        assertEquals(3, ReplyParser.parse("```json\n[\"甲\",\"乙\",\"丙\"]\n```").size)
    }
    @Test fun rejectsExplanationsEmptyOrMissingCandidates() {
        for (raw in listOf("", "建议回复：好的", "[]", "[\"甲\",\"乙\"]", "[\"\",\"乙\",\"丙\"]")) {
            assertThrows(Exception::class.java) { ReplyParser.parse(raw) }
        }
    }
    @Test fun rejectsNumbersAndDuplicates() {
        assertThrows(Exception::class.java) { ReplyParser.parse("[1,2,3]") }
        assertThrows(Exception::class.java) { ReplyParser.parse("[\"甲\",\"甲\",\"乙\"]") }
    }
    @Test fun doesNotExtractArrayFromUntrustedExplanation() {
        assertThrows(Exception::class.java) { ReplyParser.parse("下面是一些建议：[\"甲\",\"乙\",\"丙\"]") }
    }
    @Test fun refusesLongRepliesInsteadOfSilentlyTruncatingMeaning() {
        assertThrows(Exception::class.java) { ReplyParser.parse("[\"${"很".repeat(41)}\",\"乙\",\"丙\"]") }
    }
}

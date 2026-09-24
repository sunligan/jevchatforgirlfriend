package com.jev.probe.jev

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GenericJudgeCodecTest {
    private fun valid() = JSONObject("""{
        "possible_intent":"unknown","risk_level":3,"need":"unknown","reply_now":false,
        "best_action":"clarify","tension_resolved":false,"literal_question":false,"confidence":"low",
        "reason":"缺少之前约定的内容，不能确定对方所指。","missing_facts":["之前约定了什么"]
    }""")
    @Test fun parsesTypedJudgmentWithoutManufacturingProbabilities() {
        val a = GenericJudgeCodec.parse(valid().toString(), 12)
        assertFalse(a.probabilistic)
        assertEquals("low", a.confidenceLevel)
        assertEquals("unknown", a.trueIntent!!.choice)
        assertTrue(a.trueIntent!!.probabilities.isEmpty())
        assertEquals(listOf("之前约定了什么"), a.missingFacts)
        assertEquals(10, a.dangerLevel!!.maxLevel)
        assertEquals(0.0, a.shouldReplyNow!!, 0.0)
        assertEquals(12L, a.latencyMs)
        val guidance = ReplyGuidance.from(a)
        assertTrue(guidance.contains("模型自评把握"))
        assertTrue(guidance.contains("缺失事实"))
        assertFalse(guidance.contains("模型置信度：0.0"))
    }
    @Test fun malformedEnumsBooleansAndScoresFailClosed() {
        for ((key, value) in listOf("possible_intent" to "made-up", "reply_now" to "false", "risk_level" to 99, "risk_level" to 2.5, "confidence" to 0.9)) {
            assertThrows(Exception::class.java) { GenericJudgeCodec.parse(valid().put(key,value).toString(),0) }
        }
        assertThrows(Exception::class.java) { GenericJudgeCodec.parse("{}",0) }
    }
    @Test fun acceptsCodeFenceButNotExtraneousProseOrTrailingObjects() {
        assertNotNull(GenericJudgeCodec.parse("```json\n${valid()}\n```",0))
        assertThrows(Exception::class.java) { GenericJudgeCodec.parse("解释：${valid()}",0) }
        assertThrows(Exception::class.java) { GenericJudgeCodec.parse("${valid()} {}",0) }
    }
    @Test fun rankingPreservesOriginalTextAndHasNoPercentages() {
        val candidates = listOf("甲", "乙", "丙")
        val ranked = GenericJudgeCodec.ranked("""{"order":[2,0,1]}""", candidates)
        assertEquals(listOf("丙", "甲", "乙"), ranked.map { it.text })
        assertTrue(ranked.all { it.prob == null })
    }
    @Test fun rankingCannotOmitRepeatOrInventCandidates() {
        for (raw in listOf("{\"order\":[0,0,1]}", "{\"order\":[0,1]}", "{\"order\":[1,2,3]}", "{\"order\":[\"0\",1,2]}", "{\"order\":[0.5,1,2]}")) {
            assertThrows(Exception::class.java) { GenericJudgeCodec.ranked(raw,listOf("甲","乙","丙")) }
        }
    }
}

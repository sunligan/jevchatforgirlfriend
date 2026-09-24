package com.jev.probe.jev

import com.jev.probe.core.*
import org.junit.Assert.*
import org.junit.Test

class ReplyGuidanceTest {
    private fun analysis(action: String = "check_history", confidence: Double = 0.4) = Analysis(
        Choice("confirm_you_care", confidence, emptyMap()), Score(7.0, 0.8, 9),
        Choice("care", 0.8, emptyMap()), 0.2, Choice(action, 0.9, emptyMap()),
        0.1, 0.3, emptyList(), 1
    )
    @Test fun uncertainJudgmentIsNeverPresentedAsFact() {
        val prompt = ReplyGuidance.from(analysis())
        assertTrue(prompt.contains("模型推测，不是事实"))
        assertTrue(prompt.contains("信息不足"))
        assertTrue(prompt.contains("先简短承接或澄清"))
    }
    @Test fun checkHistoryConstrainsAllCandidates() {
        val prompt = ReplyGuidance.from(analysis())
        assertTrue(prompt.contains("三条都必须先核实记录"))
        assertTrue(prompt.contains("不要假装记得"))
    }
    @Test fun proposedCommitmentMustNotInventFacts() {
        assertTrue(ReplyGuidance.from(analysis("give_commitment", 0.9)).contains("不擅自承诺时间"))
    }
    @Test fun failedJudgmentDoesNotInventGuidance() {
        assertEquals("", ReplyGuidance.from(null))
        assertEquals("", ReplyGuidance.from(analysis().copy(error = "unavailable")))
    }
}

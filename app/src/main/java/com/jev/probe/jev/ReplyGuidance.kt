package com.jev.probe.jev

import com.jev.probe.core.Analysis

/** The same judgment that is shown to the user also constrains reply generation. */
object ReplyGuidance {
    fun from(a: Analysis?): String {
        if (a == null || a.error != null) return ""
        return buildString {
            append("\n本轮判断（模型推测，不是事实；不可把这些推测当作已知心理）：\n")
            a.trueIntent?.let {
                append("可能意图：${it.choice}\n")
                if (a.probabilistic) append("模型置信度：${it.confidence}\n")
                else append("模型自评把握：${a.confidenceLevel ?: "low"}（非概率）\n")
            }
            a.reasoning?.let { append("分析依据（仍需核实）：${it}\n") }
            if (a.missingFacts.isNotEmpty()) append("缺失事实：${a.missingFacts.joinToString("；")}\n")
            a.sheNeeds?.let { append("可能需要：${it.choice}\n") }
            a.bestAction?.let { append("建议动作：${it.choice}\n") }
            a.dangerLevel?.let { append("沟通风险估计：${it.score}/${it.maxLevel}\n") }
            if ((a.shouldReplyNow ?: 1.0) < 0.5) {
                append("先简短承接或澄清，不急于给实质结论、解释或承诺。\n")
            }
            when (a.bestAction?.choice) {
                "check_history", "clarify" -> append("三条都必须先核实记录或澄清缺失事实；不要假装记得，不要编造约定，不要用空泛道歉代替核实。\n")
                "say_less" -> append("保持简短，不连环追问、不施压、不写长篇辩解。\n")
                "make_plan", "give_commitment" -> append("只提出可商量的行动，不擅自承诺时间、地点、金钱或尚未确认的事项。\n")
            }
            if ((a.probabilistic && (a.trueIntent?.confidence ?: 0.0) < 0.6) || (!a.probabilistic && a.confidenceLevel != "high")) {
                append("信息不足：使用中性、可澄清的措辞，不断言对方动机。\n")
            }
            append("以上是生成约束，不得把分析标签、分数或概率写进聊天回复。\n")
        }
    }
}

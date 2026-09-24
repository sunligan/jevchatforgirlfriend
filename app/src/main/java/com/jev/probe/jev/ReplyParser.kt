package com.jev.probe.jev

import org.json.JSONArray

/** Never turn explanation text or fabricated placeholder replies into fillable candidates. */
object ReplyParser {
    fun parse(content: String): List<String> {
        val raw = content.trim().let {
            if (it.startsWith("```json") && it.endsWith("```")) it.removePrefix("```json").removeSuffix("```").trim()
            else if (it.startsWith("```") && it.endsWith("```")) it.removePrefix("```").removeSuffix("```").trim()
            else it
        }
        require(raw.startsWith("[") && raw.endsWith("]")) { "回复格式错误：需要三条候选的 JSON 数组，请重试" }
        val array = JSONArray(raw)
        require(array.length() == 3) { "回复数量错误：需要恰好三条候选，请重试" }
        val out = (0 until 3).map { i ->
            val value = array.get(i)
            require(value is String) { "候选回复必须是文字" }
            value.trim().also { text ->
                require(text.isNotBlank() && text.codePointCount(0, text.length) <= 40) {
                    "候选回复为空或超过 40 字，请重试"
                }
            }
        }
        require(out.distinct().size == 3) { "候选回复重复，请重试" }
        return out
    }
}

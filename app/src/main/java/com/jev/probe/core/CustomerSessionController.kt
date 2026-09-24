package com.jev.probe.core

/**
 * The customer beta's settings, read out as one plain value so the session
 * logic below never has to touch Android and stays unit-testable.
 */
data class CustomerConfig(
    val replyFirst: String,
    val replySecond: String,
    val category: String,
    val targetGroup: String,
    val autoSend: Boolean
)

/** What the capture service must do for one snapshot. Pure data — the service
 *  executes it, this class decides it. */
data class CustomerAction(
    val leads: List<CustomerLead>,
    val plan: CustomerReplyPlan,
    /** Auto-send is available for this plan, so the panel may offer it. */
    val autoSend: Boolean,
    /** And this snapshot is the one that should actually fire it. Suppressed
     *  once the same plan has been dispatched — see [CustomerSessionController]. */
    val dispatchNow: Boolean,
    val notify: String?
)

/**
 * Pacing for the fixed-text auto-send.
 *
 * 900 ms has to cover `fillInput`'s worst case before the next line is filled:
 * SET_TEXT plus its 150 ms verify, or the FOCUS → PASTE → verify fallback at
 * 3 × 150 ms. Filling line two while line one is still sitting unsent in the
 * composer would trip the draft guard and silently drop it.
 */
object CustomerSendSchedule {
    const val INTERVAL_MS = 900L
    fun delays(lineCount: Int): List<Long> = (0 until lineCount).map { it * INTERVAL_MS }
}

/**
 * Per-conversation state for the fixed-reply customer beta.
 *
 * Lead scanning is incremental: the "other" messages already seen in this
 * conversation are remembered, so each snapshot is scanned only for what is new
 * to it. The first cut scanned just the last incoming message, which lost a
 * phone number the moment the customer sent anything after it.
 *
 * Not thread-safe by design — the capture service calls this on the main thread
 * and hands the resulting leads to a worker for storage.
 */
class CustomerSessionController {
    private var scanned: List<String> = emptyList()
    /** Signature of the plan already dispatched in this conversation. */
    private var dispatched: String? = null

    /** Call when the conversation changes; the next scan then starts fresh. */
    fun reset() {
        scanned = emptyList()
        dispatched = null
    }

    fun onSnapshot(snapshot: ChatSnapshot, cfg: CustomerConfig): CustomerAction {
        val title = snapshot.title.orEmpty()
        val others = snapshot.messages.asSequence()
            .filter { it.side == "other" }
            .map { it.text.trim() }
            .filter { it.isNotBlank() }
            .toList()
            .takeLast(MAX_SCAN)
        val fresh = freshMessages(others)
        scanned = others

        val leads = fresh.mapNotNull {
            CustomerLeadParser.extract(it, title, cfg.category, cfg.targetGroup)
        }
        val plan = CustomerReplyLogic.plan(snapshot.messages, cfg.replyFirst, cfg.replySecond)
        val autoSend = cfg.autoSend && !plan.needsHuman && plan.lines.isNotEmpty()
        // Fire once per distinct plan per conversation.
        //
        // Without this latch the only thing standing between two auto-sends is
        // CustomerReplyLogic noticing its own line already in the tree, and on
        // Douyin "mine" is decided by `center > width/2` — an adapter heuristic
        // the QQ adapter's own comments record as unreliable, because a long
        // message's centre crosses mid-screen. During the window where a sent
        // bubble has not rendered yet (or renders left of centre) the plan looks
        // unchanged and unsent, so it would be dispatched again; fillInput's
        // draft guard does not catch it either, because a successful send is
        // exactly what empties the composer.
        val signature = plan.lines.joinToString("\u0000")
        val dispatchNow = autoSend && signature != dispatched
        if (dispatchNow) dispatched = signature

        return CustomerAction(
            leads = leads,
            plan = plan,
            autoSend = autoSend,
            dispatchNow = dispatchNow,
            notify = notification(leads, cfg.targetGroup)
        )
    }

    /**
     * Messages in [others] that the previous scan did not already cover.
     *
     * A plain append is the common case and is matched positionally. Anything
     * else — a scroll that changed which bubbles are on screen, a re-read of
     * the same window — falls back to set difference, and `CustomerLeadStore`'s
     * 24 h same-text dedupe is the last line of defence against a double save.
     */
    private fun freshMessages(others: List<String>): List<String> {
        val prev = scanned
        if (prev.isEmpty()) return others
        if (others.size > prev.size && others.subList(0, prev.size) == prev) {
            return others.drop(prev.size)
        }
        return others.filter { it !in prev }
    }

    /** One aggregated line per scan, so three leads do not beep three times. */
    private fun notification(leads: List<CustomerLead>, targetGroup: String): String? {
        if (leads.isEmpty()) return null
        val concrete = leads.count { it.phone != null || it.wechat != null }
        val hinted = leads.size - concrete
        val where = targetGroup.ifBlank { "未配置群" }
        return when {
            concrete > 0 && hinted > 0 ->
                "已保存 $concrete 条联系方式，另有 $hinted 条疑似加微信意图需人工处理；待分发到：$where"
            concrete > 0 -> "已保存 $concrete 条联系方式，待分发到：$where"
            else -> "检测到 $hinted 条疑似加微信意图，请人工处理"
        }
    }

    companion object {
        /**
         * How far back one scan looks. Without a cap, opening a long
         * conversation would harvest its whole visible history into the lead
         * queue — including numbers this operator dealt with days ago.
         */
        const val MAX_SCAN = 10
    }
}

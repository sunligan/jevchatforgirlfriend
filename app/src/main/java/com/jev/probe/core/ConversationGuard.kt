package com.jev.probe.core

/** No Android dependencies: lifecycle and request ownership are regression-testable. */
class ConversationGuard {
    data class Observation(
        val packageName: String,
        val windowId: Int,
        val title: String?,
        val signature: String,
        val allowsFill: Boolean = true
    )

    data class Ticket internal constructor(
        val generation: Long,
        val requestId: Long,
        val observation: Observation
    )

    private var generation = 0L
    private var requestId = 0L
    private var observed: Observation? = null
    private var latest: Ticket? = null

    @Synchronized fun observe(value: Observation): Boolean {
        if (observed == value) return false
        generation++
        observed = value
        latest = null
        return true
    }

    @Synchronized fun start(): Ticket? {
        val value = observed ?: return null
        return Ticket(generation, ++requestId, value).also { latest = it }
    }

    @Synchronized fun accepts(ticket: Ticket): Boolean =
        latest == ticket && generation == ticket.generation && observed == ticket.observation

    @Synchronized fun canFill(ticket: Ticket, live: Observation): Boolean =
        accepts(ticket) && live == ticket.observation && live.allowsFill &&
            live.packageName.isNotBlank() && !live.title.isNullOrBlank()

    @Synchronized fun invalidate() {
        generation++
        observed = null
        latest = null
    }
}

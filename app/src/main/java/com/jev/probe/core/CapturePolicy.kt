package com.jev.probe.core

/** Event-burst state, independent of Android, so keyboard/scroll/pause behavior is testable. */
class CapturePolicy {
    var paused: Boolean = false
        private set
    private var scrollUntil = 0L
    private var layoutUntil = 0L

    fun setPaused(value: Boolean): Boolean {
        if (paused == value) return false
        paused = value
        scrollUntil = 0L
        layoutUntil = 0L
        return true
    }
    fun onLayout(now: Long) { layoutUntil = now + SETTLE_MS }
    fun onScroll(now: Long) { scrollUntil = now + SETTLE_MS }
    fun allowAuto(now: Long): Boolean = !paused && now >= scrollUntil
    fun allowOcr(now: Long, sameConversation: Boolean): Boolean =
        !paused && (!sameConversation || (now >= layoutUntil && now >= scrollUntil))

    companion object { const val SETTLE_MS = 700L }
}

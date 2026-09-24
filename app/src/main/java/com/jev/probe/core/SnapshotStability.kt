package com.jev.probe.core

/** A tail-preserving resize is not a new message. No fuzzy matching or text-set dedupe. */
enum class SnapshotChange { INITIAL, SAME, VIEWPORT_ONLY, CONTENT_CHANGED }

object SnapshotStability {
    fun classify(previous: ChatSnapshot?, current: ChatSnapshot): SnapshotChange {
        if (previous == null) return SnapshotChange.INITIAL
        if (previous.title != current.title || previous.source != current.source) return SnapshotChange.CONTENT_CHANGED
        val before = previous.messages.takeLast(10)
        val now = current.messages.takeLast(10)
        if (before == now) return SnapshotChange.SAME
        // Unknown identity, an empty tree, or OCR uncertainty must not revive an old result.
        if (current.title.isNullOrBlank() || before.isEmpty() || now.isEmpty()) return SnapshotChange.CONTENT_CHANGED
        if (current.source == CaptureSource.SCREEN_OCR) return SnapshotChange.CONTENT_CHANGED
        if (now.size < before.size && containsContiguous(before, now)) return SnapshotChange.VIEWPORT_ONLY
        if (before.size < now.size && now.takeLast(before.size) == before && now.take(before.size) != before) {
            // More OLD messages became visible at the top (keyboard closed). A repeated
            // append could be both prefix and suffix: fail conservatively as changed.
            return SnapshotChange.VIEWPORT_ONLY
        }
        return SnapshotChange.CONTENT_CHANGED
    }

    private fun containsContiguous(haystack: List<Msg>, needle: List<Msg>): Boolean {
        if (needle.isEmpty() || needle.size > haystack.size) return false
        return haystack.windowed(needle.size).any { it == needle }
    }

    fun canonical(previous: ChatSnapshot?, current: ChatSnapshot): ChatSnapshot = when (classify(previous, current)) {
        SnapshotChange.SAME, SnapshotChange.VIEWPORT_ONLY -> previous ?: current
        else -> current
    }

    /** Even during a scroll burst a proven append should not lose a new message. */
    fun isAppend(previous: ChatSnapshot?, current: ChatSnapshot): Boolean {
        if (previous == null || previous.title.isNullOrBlank() || previous.title != current.title ||
            previous.source != current.source || previous.messages.isEmpty()) return false
        val before = previous.messages.takeLast(10)
        val now = current.messages.takeLast(10)
        if (now.size > before.size && now.take(before.size) == before) return true
        // At least two shared trailing messages establish ordering after the viewport slides up.
        for (n in minOf(before.size, now.size - 1) downTo 2) {
            if (before.takeLast(n) == now.take(n)) return true
        }
        return false
    }
}

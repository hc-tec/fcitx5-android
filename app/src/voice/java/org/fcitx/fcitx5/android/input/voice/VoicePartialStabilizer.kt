package org.fcitx.fcitx5.android.input.voice

internal class VoicePartialStabilizer {
    private var lastRendered = ""

    fun reset() {
        lastRendered = ""
    }

    fun update(
        candidate: String,
        stableLength: Int
    ): String {
        val normalized = candidate.trim()
        if (normalized.isBlank()) {
            return lastRendered
        }
        if (lastRendered.isBlank()) {
            lastRendered = normalized
            return lastRendered
        }

        val prefixLength = stableLength.coerceAtLeast(commonPrefix(lastRendered, normalized))
        if (prefixLength <= 0) {
            lastRendered = normalized
            return lastRendered
        }

        val previousTail = lastRendered.drop(prefixLength)
        val nextTail = normalized.drop(prefixLength)
        val resolvedTail =
            when {
                nextTail.length >= previousTail.length -> nextTail
                previousTail.startsWith(nextTail) -> previousTail
                else -> nextTail
            }

        lastRendered = normalized.take(prefixLength) + resolvedTail
        return lastRendered
    }

    private fun commonPrefix(
        left: String,
        right: String
    ): Int {
        val maxLength = minOf(left.length, right.length)
        var index = 0
        while (index < maxLength && left[index] == right[index]) {
            index += 1
        }
        return index
    }
}

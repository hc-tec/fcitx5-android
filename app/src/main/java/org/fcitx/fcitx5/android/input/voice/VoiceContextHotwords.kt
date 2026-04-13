package org.fcitx.fcitx5.android.input.voice

internal object VoiceContextHotwords {
    private val latinPhraseRegex =
        Regex("\\b[A-Za-z][A-Za-z0-9._+\\-]*(?:\\s+[A-Za-z][A-Za-z0-9._+\\-]*){0,2}\\b")
    private val hanPhraseRegex = Regex("[\\p{IsHan}]{2,12}")

    fun extract(
        selectedText: String,
        composingText: String,
        leftContext: String,
        maxItems: Int = 8
    ): List<String> {
        val hotwords = LinkedHashSet<String>()
        addCandidates(hotwords, selectedText, allowWholePhrase = true)
        addCandidates(hotwords, composingText, allowWholePhrase = true)
        addCandidates(hotwords, leftContext.takeLast(96), allowWholePhrase = false)
        return hotwords.take(maxItems)
    }

    fun formatForSherpaStream(hotwords: List<String>): String =
        hotwords
            .map { it.replace("[\\r\\n/]+".toRegex(), " ").replace("\\s+".toRegex(), " ").trim() }
            .filter(String::isNotBlank)
            .joinToString(separator = "/")

    private fun addCandidates(
        sink: LinkedHashSet<String>,
        text: String,
        allowWholePhrase: Boolean
    ) {
        val normalized = text.trim().replace("\\s+".toRegex(), " ")
        if (normalized.isBlank()) {
            return
        }

        if (allowWholePhrase && normalized.length in 2..24 && normalized.none { it == '\n' || it == '\r' }) {
            addUnique(sink, normalized)
        }

        latinPhraseRegex.findAll(normalized)
            .map { it.value.trim() }
            .filter { it.length >= 4 }
            .forEach { addUnique(sink, it) }

        hanPhraseRegex.findAll(normalized)
            .map { it.value.trim() }
            .filter { it.length >= 2 }
            .forEach { addUnique(sink, it) }
    }

    private fun addUnique(
        sink: LinkedHashSet<String>,
        candidate: String
    ) {
        val normalized = normalize(candidate)
        if (normalized.isBlank()) {
            return
        }
        if (sink.any { normalize(it) == normalized }) {
            return
        }
        sink += candidate
    }

    private fun normalize(value: String): String =
        value
            .replace("\\s+".toRegex(), " ")
            .trim()
            .lowercase()
}

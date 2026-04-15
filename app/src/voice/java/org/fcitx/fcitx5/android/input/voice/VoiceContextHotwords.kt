package org.fcitx.fcitx5.android.input.voice

internal object VoiceContextHotwords {
    private val latinPhraseRegex =
        Regex("\\b[A-Za-z][A-Za-z0-9._+\\-]*(?:\\s+[A-Za-z][A-Za-z0-9._+\\-]*){0,2}\\b")
    private val hanPhraseRegex = Regex("[\\p{IsHan}]{2,12}")
    private val camelCaseBoundaryRegex = Regex("(?<=[a-z0-9])(?=[A-Z])")

    fun extract(
        selectedText: String,
        composingText: String,
        leftContext: String,
        rightContext: String = "",
        maxItems: Int = 12
    ): List<String> {
        val hotwords = LinkedHashSet<String>()
        addCandidates(hotwords, selectedText, allowWholePhrase = true)
        addCandidates(hotwords, composingText, allowWholePhrase = true)
        addCandidates(hotwords, leftContext.takeLast(192), allowWholePhrase = false)
        addCandidates(hotwords, rightContext.take(96), allowWholePhrase = false)
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
            addExpandedCandidates(sink, normalized)
        }

        latinPhraseRegex.findAll(normalized)
            .map { it.value.trim() }
            .filter { it.length >= 4 }
            .forEach { addExpandedCandidates(sink, it) }

        hanPhraseRegex.findAll(normalized)
            .map { it.value.trim() }
            .filter { it.length >= 2 }
            .forEach { addUnique(sink, it) }
    }

    private fun addExpandedCandidates(
        sink: LinkedHashSet<String>,
        candidate: String
    ) {
        latinVariants(candidate).forEach { addUnique(sink, it) }
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

    private fun latinVariants(candidate: String): List<String> {
        val normalized = candidate.replace("\\s+".toRegex(), " ").trim()
        if (normalized.isBlank() || normalized.none { it.isLetter() && it.code < 128 }) {
            return listOf(normalized)
        }

        val variants = linkedSetOf<String>()
        variants += normalized

        val separated =
            normalized
                .replace(camelCaseBoundaryRegex, " ")
                .replace("[._+\\-]+".toRegex(), " ")
                .replace("\\s+".toRegex(), " ")
                .trim()
        if (separated.isNotBlank()) {
            variants += separated
        }

        val compact = separated.replace(" ", "")
        if (compact.length >= 4) {
            variants += compact
        }

        val words = separated.split(' ').filter(String::isNotBlank)
        if (words.size in 2..3) {
            val titleCase =
                words.joinToString(" ") {
                    val lower = it.lowercase()
                    lower.replaceFirstChar { ch -> ch.titlecase() }
                }
            variants += titleCase
            val joinedTitleCase =
                words.joinToString("") {
                    val lower = it.lowercase()
                    lower.replaceFirstChar { ch -> ch.titlecase() }
                }
            variants += joinedTitleCase
        }

        return variants.toList()
    }
}

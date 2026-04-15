package org.fcitx.fcitx5.android.voice.core

import kotlin.math.max

class VoicePostProcessor(
    private val correctionStore: VoiceCorrectionStore
) {
    private val latinPhraseRegex =
        Regex("\\b([A-Za-z][A-Za-z'\\-]*(?:\\s+[A-Za-z][A-Za-z'\\-]*){0,2})\\b")
    private val latinWordRegex = Regex("\\b[A-Za-z][A-Za-z'\\-]*\\b")

    fun process(
        result: VoiceRecognitionResult,
        context: VoiceContextSnapshot,
        isFinal: Boolean
    ): VoiceProcessedResult {
        val rawText = normalizeSpaces(result.text)
        var working = rawText
        val lowConfidenceSpans = mutableListOf<VoiceLowConfidenceSpan>()

        correctionStore.findEntries(context.locale, context.packageName).forEach { entry ->
            if (working.contains(entry.wrong, ignoreCase = true)) {
                working = replaceIgnoreCase(working, entry.wrong, entry.correct)
                lowConfidenceSpans += VoiceLowConfidenceSpan(entry.correct, "correction_memory")
            }
        }

        working = applyEntityBias(working, context, lowConfidenceSpans)
        if (isFinal) {
            working = punctuate(working, context.locale)
        }

        val alternatives =
            linkedSetOf<String>().apply {
                if (working.isNotBlank()) add(working)
                if (rawText.isNotBlank() && rawText != working) add(rawText)
                result.alternatives.map(::normalizeSpaces).filter(String::isNotBlank).forEach(::add)
            }.toList()

        return VoiceProcessedResult(
            text = working,
            alternatives = alternatives,
            lowConfidenceSpans = lowConfidenceSpans,
            learnedEntities = extractEntities(working, context),
            confidence = result.confidence
        )
    }

    private fun applyEntityBias(
        input: String,
        context: VoiceContextSnapshot,
        lowConfidenceSpans: MutableList<VoiceLowConfidenceSpan>
    ): String {
        val candidates =
            buildList {
                addAll(context.hotwords)
                addAll(context.sessionEntities)
                addAll(extractDynamicCandidates(context.selectedText))
                addAll(extractDynamicCandidates(context.composingText))
                addAll(extractDynamicCandidates(context.leftContext))
                correctionStore.findEntries(context.locale, context.packageName).forEach {
                    add(it.correct)
                }
            }
                .map(String::trim)
                .filter(String::isNotBlank)
                .distinctBy(::normalizeToken)

        val windowRewritten = rewriteTokenWindows(input, candidates, lowConfidenceSpans)
        return latinPhraseRegex.replace(windowRewritten) { match ->
            val phrase = match.groupValues[1]
            val best = bestCandidate(phrase, candidates)
            if (best != null && normalizeToken(phrase) != normalizeToken(best)) {
                lowConfidenceSpans += VoiceLowConfidenceSpan(best, "entity_bias")
                best
            } else {
                phrase
            }
        }
    }

    private fun rewriteTokenWindows(
        input: String,
        candidates: List<String>,
        lowConfidenceSpans: MutableList<VoiceLowConfidenceSpan>
    ): String {
        val tokens =
            latinWordRegex.findAll(input)
                .map { match -> Token(match.value, match.range.first, match.range.last + 1) }
                .toList()
        if (tokens.isEmpty()) {
            return input
        }

        val builder = StringBuilder()
        var cursor = 0
        var index = 0
        while (index < tokens.size) {
            val replacement = bestWindowReplacement(tokens, index, candidates)
            if (replacement == null) {
                index += 1
                continue
            }

            val first = tokens[index]
            builder.append(input.substring(cursor, first.start))
            builder.append(replacement.candidate)
            cursor = tokens[replacement.endIndex].end
            lowConfidenceSpans += VoiceLowConfidenceSpan(replacement.candidate, "entity_bias_window")
            index = replacement.endIndex + 1
        }

        builder.append(input.substring(cursor))
        return if (builder.isEmpty()) input else builder.toString()
    }

    private fun bestWindowReplacement(
        tokens: List<Token>,
        startIndex: Int,
        candidates: List<String>
    ): Replacement? {
        var best: Replacement? = null
        for (size in 2 downTo 1) {
            val endIndex = startIndex + size - 1
            if (endIndex >= tokens.size) {
                continue
            }

            val phrase = joinTokens(tokens, startIndex, endIndex)
            val phraseNorm = normalizeToken(phrase)
            if (phraseNorm.length < 5) {
                continue
            }

            candidates.forEach { candidate ->
                val candidateNorm = normalizeToken(candidate)
                if (candidateNorm.isBlank() || candidateNorm == phraseNorm) {
                    return@forEach
                }
                val score = similarity(phraseNorm, candidateNorm) + prefixBoost(phraseNorm, candidateNorm)
                if (score >= 0.62 && score > (best?.score ?: Double.NEGATIVE_INFINITY)) {
                    best = Replacement(candidate, endIndex, score)
                }
            }
        }
        return best
    }

    private fun joinTokens(
        tokens: List<Token>,
        startIndex: Int,
        endIndex: Int
    ): String =
        buildString {
            for (index in startIndex..endIndex) {
                if (isNotEmpty()) append(' ')
                append(tokens[index].text)
            }
        }

    private fun bestCandidate(
        phrase: String,
        candidates: List<String>
    ): String? {
        val phraseNorm = normalizeToken(phrase)
        if (phraseNorm.length < 5) {
            return null
        }

        var bestCandidate: String? = null
        var bestScore = 0.0
        candidates.forEach { candidate ->
            val candidateNorm = normalizeToken(candidate)
            if (candidateNorm.isBlank() || candidateNorm == phraseNorm) {
                return@forEach
            }
            val score = similarity(phraseNorm, candidateNorm) + prefixBoost(phraseNorm, candidateNorm)
            if (score > bestScore) {
                bestScore = score
                bestCandidate = candidate
            }
        }
        return if (bestScore >= 0.62) bestCandidate else null
    }

    internal fun similarity(
        left: String,
        right: String
    ): Double {
        val maxLength = max(left.length, right.length)
        if (maxLength == 0) {
            return 1.0
        }
        val distance = levenshtein(left, right)
        return 1.0 - (distance.toDouble() / maxLength.toDouble())
    }

    internal fun levenshtein(
        left: String,
        right: String
    ): Int {
        val dp = Array(left.length + 1) { IntArray(right.length + 1) }
        for (index in 0..left.length) {
            dp[index][0] = index
        }
        for (index in 0..right.length) {
            dp[0][index] = index
        }
        for (i in 1..left.length) {
            for (j in 1..right.length) {
                val cost = if (left[i - 1] == right[j - 1]) 0 else 1
                dp[i][j] =
                    minOf(
                        dp[i - 1][j] + 1,
                        dp[i][j - 1] + 1,
                        dp[i - 1][j - 1] + cost
                    )
            }
        }
        return dp[left.length][right.length]
    }

    private fun prefixBoost(
        left: String,
        right: String
    ): Double {
        val maxPrefix = minOf(left.length, right.length, 6)
        var prefix = 0
        while (prefix < maxPrefix && left[prefix] == right[prefix]) {
            prefix += 1
        }
        return if (prefix >= 4) 0.08 else 0.0
    }

    private fun punctuate(
        text: String,
        locale: String
    ): String {
        if (text.isBlank()) {
            return text
        }
        val tail = text.last()
        if ("。！？.!?".contains(tail)) {
            return text
        }
        return if (locale.lowercase().startsWith("zh") || locale.lowercase().startsWith("yue")) {
            "$text。"
        } else {
            "$text."
        }
    }

    private fun extractEntities(
        text: String,
        context: VoiceContextSnapshot
    ): List<String> {
        val result = linkedSetOf<String>()
        context.hotwords
            .filter(String::isNotBlank)
            .filter { hotword -> text.contains(hotword, ignoreCase = true) }
            .forEach(result::add)

        latinPhraseRegex.findAll(text).forEach { match ->
            val phrase = match.groupValues[1].trim()
            if (normalizeToken(phrase).length >= 5) {
                result += phrase
            }
        }
        return result.toList()
    }

    private fun extractDynamicCandidates(text: String): List<String> =
        latinPhraseRegex.findAll(text)
            .map { it.groupValues[1].trim() }
            .filter { normalizeToken(it).length >= 5 }
            .toList()

    internal fun normalizeToken(value: String?): String =
        value.orEmpty().replace("[^A-Za-z0-9]".toRegex(), "").lowercase()

    private fun normalizeSpaces(text: String): String = text.trim().replace("\\s+".toRegex(), " ")

    private fun replaceIgnoreCase(
        text: String,
        target: String,
        replacement: String
    ): String =
        Regex(Regex.escape(target), setOf(RegexOption.IGNORE_CASE)).replace(text, replacement)

    private data class Token(
        val text: String,
        val start: Int,
        val end: Int
    )

    private data class Replacement(
        val candidate: String,
        val endIndex: Int,
        val score: Double
    )
}

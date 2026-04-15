package org.fcitx.fcitx5.android.input.voice

internal object VoiceCorrectionDiffDetector {
    data class CorrectionMatch(
        val originalText: String,
        val correctedText: String
    )

    data class TextSpan(
        val start: Int,
        val end: Int
    ) {
        val isEmpty: Boolean
            get() = start >= end
    }

    fun detect(
        baselineText: String,
        committedSpan: TextSpan,
        currentText: String
    ): CorrectionMatch? {
        if (baselineText.isBlank() || currentText.isBlank() || baselineText == currentText) {
            return null
        }
        if (committedSpan.start < 0 || committedSpan.end > baselineText.length || committedSpan.start >= committedSpan.end) {
            return null
        }

        val prefixLength = commonPrefixLength(baselineText, currentText)
        val suffixLength = commonSuffixLength(baselineText, currentText, prefixLength)
        val baselineChanged = TextSpan(prefixLength, baselineText.length - suffixLength)
        val currentChanged = TextSpan(prefixLength, currentText.length - suffixLength)

        if (!changeTouchesCommittedSpan(baselineChanged, committedSpan)) {
            return null
        }

        val originalSpan = expandPhraseSpan(baselineText, baselineChanged)
        val correctedSpan = expandPhraseSpan(currentText, currentChanged)
        val originalText = baselineText.substring(originalSpan.start, originalSpan.end).trim()
        val correctedText = currentText.substring(correctedSpan.start, correctedSpan.end).trim()
        if (originalText.isBlank() || correctedText.isBlank() || originalText == correctedText) {
            return null
        }
        if (originalText.length !in 2..64 || correctedText.length !in 2..64) {
            return null
        }
        if (!looksLearnable(originalText) || !looksLearnable(correctedText)) {
            return null
        }

        val originalNormalized = normalizeForHeuristics(originalText)
        val correctedNormalized = normalizeForHeuristics(correctedText)
        if (originalNormalized.isBlank() || correctedNormalized.isBlank()) {
            return null
        }
        if (
            (originalNormalized.startsWith(correctedNormalized) || correctedNormalized.startsWith(originalNormalized)) &&
            kotlin.math.abs(originalNormalized.length - correctedNormalized.length) <= 2
        ) {
            return null
        }

        return CorrectionMatch(
            originalText = originalText,
            correctedText = correctedText
        )
    }

    private fun changeTouchesCommittedSpan(
        changedSpan: TextSpan,
        committedSpan: TextSpan
    ): Boolean {
        if (!changedSpan.isEmpty) {
            return changedSpan.start < committedSpan.end && changedSpan.end > committedSpan.start
        }
        return changedSpan.start in committedSpan.start..committedSpan.end
    }

    private fun commonPrefixLength(
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

    private fun commonSuffixLength(
        left: String,
        right: String,
        sharedPrefixLength: Int
    ): Int {
        val maxLength = minOf(left.length, right.length) - sharedPrefixLength
        var offset = 0
        while (
            offset < maxLength &&
            left[left.length - 1 - offset] == right[right.length - 1 - offset]
        ) {
            offset += 1
        }
        return offset
    }

    private fun expandPhraseSpan(
        text: String,
        changedSpan: TextSpan
    ): TextSpan {
        if (text.isEmpty()) {
            return TextSpan(0, 0)
        }

        var start = changedSpan.start.coerceIn(0, text.length)
        var end = changedSpan.end.coerceIn(start, text.length)
        val mode = detectExpansionMode(text, changedSpan)

        while (start > 0) {
            val previous = text[start - 1]
            if (isExpansionChar(previous, mode)) {
                start -= 1
                continue
            }
            if (
                mode == ExpansionMode.Latin &&
                isBridgeChar(previous) &&
                start > 1 &&
                start < text.length &&
                isExpansionChar(text[start], mode) &&
                isExpansionChar(text[start - 2], mode)
            ) {
                start -= 1
                continue
            }
            break
        }

        while (end < text.length) {
            val current = text[end]
            if (isExpansionChar(current, mode)) {
                end += 1
                continue
            }
            if (
                mode == ExpansionMode.Latin &&
                isBridgeChar(current) &&
                end > 0 &&
                end + 1 < text.length &&
                isExpansionChar(text[end - 1], mode) &&
                isExpansionChar(text[end + 1], mode)
            ) {
                end += 1
                continue
            }
            break
        }

        return TextSpan(start, end)
    }

    private fun detectExpansionMode(
        text: String,
        changedSpan: TextSpan
    ): ExpansionMode {
        val sampleStart = (changedSpan.start - 1).coerceAtLeast(0)
        val sampleEnd = (changedSpan.end + 1).coerceAtMost(text.length)
        val sample = text.substring(sampleStart, sampleEnd)
        return when {
            sample.any { it.code < 128 && it.isLetterOrDigit() } -> ExpansionMode.Latin
            sample.any(::isHanCharacter) -> ExpansionMode.Han
            else -> ExpansionMode.Generic
        }
    }

    private fun looksLearnable(value: String): Boolean {
        val hanCount = value.count(::isHanCharacter)
        val latinOrDigitCount = value.count { it.code < 128 && it.isLetterOrDigit() }
        return hanCount >= 2 || latinOrDigitCount >= 4
    }

    private fun normalizeForHeuristics(value: String): String =
        value
            .replace("[\\s._+\\-]+".toRegex(), "")
            .lowercase()

    private fun isExpansionChar(
        character: Char,
        mode: ExpansionMode
    ): Boolean =
        when (mode) {
            ExpansionMode.Latin -> character.code < 128 && character.isLetterOrDigit()
            ExpansionMode.Han -> isHanCharacter(character)
            ExpansionMode.Generic -> isPhraseChar(character)
        }

    private fun isPhraseChar(character: Char): Boolean =
        character.isLetterOrDigit() || isHanCharacter(character)

    private fun isBridgeChar(character: Char): Boolean =
        character.isWhitespace() || character == '.' || character == '_' || character == '-' || character == '+'

    private fun isHanCharacter(character: Char): Boolean =
        Character.UnicodeScript.of(character.code) == Character.UnicodeScript.HAN

    private enum class ExpansionMode {
        Latin,
        Han,
        Generic
    }
}

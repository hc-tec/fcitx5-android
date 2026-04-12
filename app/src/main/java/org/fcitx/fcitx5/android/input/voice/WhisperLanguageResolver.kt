package org.fcitx.fcitx5.android.input.voice

import java.util.Locale

internal object WhisperLanguageResolver {
    fun resolve(localeTag: String): String {
        val tag = localeTag.trim()
        if (tag.isBlank()) {
            return DEFAULT_LANGUAGE
        }

        val language =
            runCatching { Locale.forLanguageTag(tag).language }
                .getOrNull()
                .orEmpty()
                .ifBlank { tag.substringBefore('-').substringBefore('_') }
                .lowercase()

        return when (language) {
            "cmn", "zh", "yue" -> "zh"
            else -> language.ifBlank { DEFAULT_LANGUAGE }
        }
    }

    private const val DEFAULT_LANGUAGE = "en"
}

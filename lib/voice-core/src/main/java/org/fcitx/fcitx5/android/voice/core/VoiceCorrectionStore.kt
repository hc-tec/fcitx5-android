package org.fcitx.fcitx5.android.voice.core

interface VoiceCorrectionStore {
    fun learn(feedback: VoiceCorrectionFeedback)

    fun findEntries(
        locale: String,
        packageName: String
    ): List<CorrectionEntry>

    data class CorrectionEntry(
        val wrong: String,
        val correct: String,
        val packageName: String,
        val locale: String,
        val weight: Double,
        val updatedAtEpochMs: Long
    )
}

class InMemoryVoiceCorrectionStore : VoiceCorrectionStore {
    private val entries = linkedMapOf<String, VoiceCorrectionStore.CorrectionEntry>()

    override fun learn(feedback: VoiceCorrectionFeedback) {
        if (feedback.originalText.isBlank() || feedback.correctedText.isBlank()) {
            return
        }

        val key = buildKey(feedback.locale, feedback.packageName, feedback.originalText)
        val now = System.currentTimeMillis()
        val existing = entries[key]
        entries[key] =
            if (existing == null) {
                VoiceCorrectionStore.CorrectionEntry(
                    wrong = feedback.originalText,
                    correct = feedback.correctedText,
                    packageName = feedback.packageName,
                    locale = feedback.locale,
                    weight = 1.0,
                    updatedAtEpochMs = now
                )
            } else {
                existing.copy(
                    correct = feedback.correctedText,
                    weight = existing.weight + 1.0,
                    updatedAtEpochMs = now
                )
            }
    }

    override fun findEntries(
        locale: String,
        packageName: String
    ): List<VoiceCorrectionStore.CorrectionEntry> =
        entries.values
            .filter { entry ->
                val localeMatches = entry.locale.isBlank() || entry.locale.equals(locale, ignoreCase = true)
                val packageMatches = entry.packageName.isBlank() || entry.packageName.equals(packageName, ignoreCase = true)
                localeMatches && packageMatches
            }
            .sortedWith(
                compareByDescending<VoiceCorrectionStore.CorrectionEntry> { it.weight }
                    .thenByDescending { it.updatedAtEpochMs }
            )

    private fun buildKey(
        locale: String,
        packageName: String,
        originalText: String
    ): String =
        listOf(locale, packageName, originalText)
            .joinToString("|") { value -> value.trim().lowercase() }
}

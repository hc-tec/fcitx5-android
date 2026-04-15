package org.fcitx.fcitx5.android.input.voice

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.voice.core.VoiceCorrectionFeedback
import org.fcitx.fcitx5.android.voice.core.VoiceCorrectionStore

internal class VoicePersonalizationStore(
    private val storage: SnapshotStorage,
    private val clock: () -> Long = System::currentTimeMillis
) : VoiceCorrectionStore {

    private val lock = Any()
    private var snapshot = loadSnapshot()

    override fun learn(feedback: VoiceCorrectionFeedback) {
        val wrong = sanitizeText(feedback.originalText)
        val correct = sanitizeText(feedback.correctedText)
        if (wrong.isBlank() || correct.isBlank()) {
            return
        }

        synchronized(lock) {
            val now = clock()
            val index =
                snapshot.corrections.indexOfFirst {
                    it.locale.equals(feedback.locale, ignoreCase = true) &&
                        it.packageName.equals(feedback.packageName, ignoreCase = true) &&
                        normalizePhraseKey(it.wrong) == normalizePhraseKey(wrong)
                }
            val updated =
                if (index >= 0) {
                    snapshot.corrections[index].copy(
                        correct = correct,
                        weight = snapshot.corrections[index].weight + 1.0,
                        updatedAtEpochMs = now
                    )
                } else {
                    StoredCorrection(
                        wrong = wrong,
                        correct = correct,
                        packageName = feedback.packageName.trim(),
                        locale = feedback.locale.trim(),
                        weight = 1.0,
                        updatedAtEpochMs = now
                    )
                }

            snapshot =
                snapshot.copy(
                    corrections =
                        snapshot.corrections
                            .toMutableList()
                            .apply {
                                if (index >= 0) {
                                    set(index, updated)
                                } else {
                                    add(updated)
                                }
                            }
                            .sortedCorrections(feedback.locale, feedback.packageName)
                            .take(MaxCorrectionEntries),
                    hotwords =
                        rememberHotwordsLocked(
                            entries = snapshot.hotwords,
                            locale = feedback.locale,
                            packageName = feedback.packageName,
                            phrases = listOf(correct),
                            baseWeight = 1.25,
                            mirrorToGlobal = true,
                            now = now
                        )
                )
            saveSnapshotLocked()
        }
    }

    override fun findEntries(
        locale: String,
        packageName: String
    ): List<VoiceCorrectionStore.CorrectionEntry> =
        synchronized(lock) {
            snapshot.corrections
                .filter { entry ->
                    localeMatches(entry.locale, locale) && packageMatches(entry.packageName, packageName)
                }
                .sortedCorrections(locale, packageName)
                .map {
                    VoiceCorrectionStore.CorrectionEntry(
                        wrong = it.wrong,
                        correct = it.correct,
                        packageName = it.packageName,
                        locale = it.locale,
                        weight = it.weight,
                        updatedAtEpochMs = it.updatedAtEpochMs
                    )
                }
        }

    fun suggestHotwords(
        locale: String,
        packageName: String,
        seedHotwords: List<String>,
        maxItems: Int = 12
    ): List<String> =
        synchronized(lock) {
            val merged = LinkedHashSet<String>()
            seedHotwords.forEach { addUniquePhrase(merged, it) }

            snapshot.hotwords
                .filter { entry ->
                    localeMatches(entry.locale, locale) && packageMatches(entry.packageName, packageName)
                }
                .sortedHotwords(locale, packageName)
                .forEach { addUniquePhrase(merged, it.phrase) }

            snapshot.corrections
                .filter { entry ->
                    localeMatches(entry.locale, locale) && packageMatches(entry.packageName, packageName)
                }
                .sortedCorrections(locale, packageName)
                .forEach { addUniquePhrase(merged, it.correct) }

            merged.take(maxItems)
        }

    fun rememberAcceptedPhrases(
        locale: String,
        packageName: String,
        phrases: List<String>
    ) {
        synchronized(lock) {
            val updatedHotwords =
                rememberHotwordsLocked(
                    entries = snapshot.hotwords,
                    locale = locale,
                    packageName = packageName,
                    phrases = phrases,
                    baseWeight = 1.0,
                    mirrorToGlobal = true,
                    now = clock()
                )
            if (updatedHotwords == snapshot.hotwords) {
                return
            }
            snapshot = snapshot.copy(hotwords = updatedHotwords)
            saveSnapshotLocked()
        }
    }

    private fun rememberHotwordsLocked(
        entries: List<StoredHotword>,
        locale: String,
        packageName: String,
        phrases: List<String>,
        baseWeight: Double,
        mirrorToGlobal: Boolean,
        now: Long
    ): List<StoredHotword> {
        if (phrases.isEmpty()) {
            return entries
        }

        val updated = entries.toMutableList()
        phrases
            .map(::sanitizeLearnedPhrase)
            .filter(String::isNotBlank)
            .forEach { phrase ->
                upsertHotword(updated, phrase, locale, packageName, baseWeight, now)
                if (mirrorToGlobal && packageName.isNotBlank()) {
                    upsertHotword(updated, phrase, locale, "", baseWeight * GlobalMirrorWeight, now)
                }
            }

        return updated
            .sortedHotwords(locale, packageName)
            .take(MaxHotwordEntries)
    }

    private fun upsertHotword(
        entries: MutableList<StoredHotword>,
        phrase: String,
        locale: String,
        packageName: String,
        weightDelta: Double,
        now: Long
    ) {
        val index =
            entries.indexOfFirst {
                it.locale.equals(locale, ignoreCase = true) &&
                    it.packageName.equals(packageName, ignoreCase = true) &&
                    normalizePhraseKey(it.phrase) == normalizePhraseKey(phrase)
            }
        if (index >= 0) {
            val existing = entries[index]
            entries[index] =
                existing.copy(
                    phrase = phrase,
                    weight = existing.weight + weightDelta,
                    updatedAtEpochMs = now
                )
        } else {
            entries +=
                StoredHotword(
                    phrase = phrase,
                    packageName = packageName.trim(),
                    locale = locale.trim(),
                    weight = weightDelta,
                    updatedAtEpochMs = now
                )
        }
    }

    private fun saveSnapshotLocked() {
        storage.write(json.encodeToString(VoicePersonalizationSnapshot.serializer(), snapshot))
    }

    private fun loadSnapshot(): VoicePersonalizationSnapshot {
        val raw = storage.read()?.trim().orEmpty()
        if (raw.isBlank()) {
            return VoicePersonalizationSnapshot()
        }
        return runCatching {
            json.decodeFromString(VoicePersonalizationSnapshot.serializer(), raw)
        }.getOrDefault(VoicePersonalizationSnapshot())
    }

    private fun addUniquePhrase(
        sink: LinkedHashSet<String>,
        candidate: String
    ) {
        val phrase = sanitizeLearnedPhrase(candidate)
        if (phrase.isBlank()) {
            return
        }
        val normalized = normalizePhraseKey(phrase)
        if (sink.any { normalizePhraseKey(it) == normalized }) {
            return
        }
        sink += phrase
    }

    private fun localeMatches(
        storedLocale: String,
        locale: String
    ): Boolean = storedLocale.isBlank() || storedLocale.equals(locale, ignoreCase = true)

    private fun packageMatches(
        storedPackageName: String,
        packageName: String
    ): Boolean = storedPackageName.isBlank() || storedPackageName.equals(packageName, ignoreCase = true)

    private fun sanitizeText(value: String): String = value.replace("\\s+".toRegex(), " ").trim()

    private fun sanitizeLearnedPhrase(value: String): String {
        val phrase = sanitizeText(value)
        if (phrase.length !in 2..48) {
            return ""
        }
        val hanCount = phrase.count(::isHanCharacter)
        val asciiLetterOrDigitCount = phrase.count { it.code < 128 && it.isLetterOrDigit() }
        return if (hanCount >= 2 || asciiLetterOrDigitCount >= 4) phrase else ""
    }

    private fun normalizePhraseKey(value: String): String =
        value
            .replace("[\\s._+\\-]+".toRegex(), "")
            .trim()
            .lowercase()

    private fun isHanCharacter(character: Char): Boolean =
        Character.UnicodeScript.of(character.code) == Character.UnicodeScript.HAN

    private fun List<StoredHotword>.sortedHotwords(
        locale: String,
        packageName: String
    ): List<StoredHotword> =
        sortedWith(
            compareByDescending<StoredHotword> { it.packageName.equals(packageName, ignoreCase = true) }
                .thenByDescending { it.locale.equals(locale, ignoreCase = true) }
                .thenByDescending { it.weight }
                .thenByDescending { it.updatedAtEpochMs }
        )

    private fun List<StoredCorrection>.sortedCorrections(
        locale: String,
        packageName: String
    ): List<StoredCorrection> =
        sortedWith(
            compareByDescending<StoredCorrection> { it.packageName.equals(packageName, ignoreCase = true) }
                .thenByDescending { it.locale.equals(locale, ignoreCase = true) }
                .thenByDescending { it.weight }
                .thenByDescending { it.updatedAtEpochMs }
        )

    internal interface SnapshotStorage {
        fun read(): String?

        fun write(value: String)
    }

    private class SharedPreferencesSnapshotStorage(
        private val sharedPreferences: SharedPreferences
    ) : SnapshotStorage {
        override fun read(): String? = sharedPreferences.getString(SnapshotKey, null)

        override fun write(value: String) {
            sharedPreferences.edit {
                putString(SnapshotKey, value)
            }
        }
    }

    @Serializable
    private data class VoicePersonalizationSnapshot(
        val corrections: List<StoredCorrection> = emptyList(),
        val hotwords: List<StoredHotword> = emptyList()
    )

    @Serializable
    private data class StoredCorrection(
        val wrong: String,
        val correct: String,
        val packageName: String,
        val locale: String,
        val weight: Double,
        val updatedAtEpochMs: Long
    )

    @Serializable
    private data class StoredHotword(
        val phrase: String,
        val packageName: String,
        val locale: String,
        val weight: Double,
        val updatedAtEpochMs: Long
    )

    companion object {
        private const val PreferenceName = "voice_personalization"
        private const val SnapshotKey = "voice_personalization_snapshot_v1"
        private const val MaxCorrectionEntries = 96
        private const val MaxHotwordEntries = 192
        private const val GlobalMirrorWeight = 0.35

        private val json =
            Json {
                ignoreUnknownKeys = true
            }

        fun create(
            context: Context = FcitxApplication.getInstance().directBootAwareContext
        ): VoicePersonalizationStore {
            val sharedPreferences =
                context.getSharedPreferences(PreferenceName, Context.MODE_PRIVATE)
            return VoicePersonalizationStore(SharedPreferencesSnapshotStorage(sharedPreferences))
        }
    }
}

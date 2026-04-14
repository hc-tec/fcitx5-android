package org.fcitx.fcitx5.android.input.voice

import org.fcitx.fcitx5.android.voice.core.VoiceCorrectionFeedback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoicePersonalizationStoreTest {
    @Test
    fun rememberedHotwordsSurviveRepositoryReload() {
        val storage = InMemorySnapshotStorage()
        val first = VoicePersonalizationStore(storage) { 1_000L }

        first.rememberAcceptedPhrases(
            locale = "zh-CN",
            packageName = "com.example.chat",
            phrases = listOf("OpenClaw", "KeyFlow")
        )

        val second = VoicePersonalizationStore(storage) { 2_000L }
        val suggestions =
            second.suggestHotwords(
                locale = "zh-CN",
                packageName = "com.example.chat",
                seedHotwords = emptyList()
            )

        assertEquals(listOf("OpenClaw", "KeyFlow"), suggestions.take(2))
    }

    @Test
    fun seedHotwordsStayAheadOfPersonalMemory() {
        val storage = InMemorySnapshotStorage()
        val store = VoicePersonalizationStore(storage) { 1_000L }

        store.rememberAcceptedPhrases(
            locale = "zh-CN",
            packageName = "com.example.chat",
            phrases = listOf("OpenClaw")
        )

        val suggestions =
            store.suggestHotwords(
                locale = "zh-CN",
                packageName = "com.example.chat",
                seedHotwords = listOf("KeyFlow", "OpenClaw")
            )

        assertEquals("KeyFlow", suggestions.first())
        assertEquals(1, suggestions.count { it.equals("OpenClaw", ignoreCase = true) })
    }

    @Test
    fun correctionsAlsoFeedHotwordRecallAcrossApps() {
        val storage = InMemorySnapshotStorage()
        val first = VoicePersonalizationStore(storage) { 1_000L }

        first.learn(
            VoiceCorrectionFeedback(
                sessionId = "voice_1",
                originalText = "Open Cloud",
                correctedText = "OpenClaw",
                packageName = "com.example.chat",
                locale = "zh-CN"
            )
        )

        val second = VoicePersonalizationStore(storage) { 2_000L }
        val suggestions =
            second.suggestHotwords(
                locale = "zh-CN",
                packageName = "com.example.notes",
                seedHotwords = emptyList()
            )

        assertTrue(suggestions.contains("OpenClaw"))
    }

    private class InMemorySnapshotStorage : VoicePersonalizationStore.SnapshotStorage {
        private var value: String? = null

        override fun read(): String? = value

        override fun write(value: String) {
            this.value = value
        }
    }
}

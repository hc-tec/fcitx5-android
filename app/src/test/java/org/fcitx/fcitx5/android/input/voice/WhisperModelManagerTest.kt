package org.fcitx.fcitx5.android.input.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WhisperModelManagerTest {
    @Test
    fun `prefers base model family first`() {
        val result =
            WhisperModelManager.resolveModel(
                arrayOf("ggml-small.bin", "ggml-base-q5_1.bin", "ggml-tiny.bin")
            )

        assertEquals("base-q5_1", result?.modelId)
        assertEquals("models/ggml-base-q5_1.bin", result?.assetPath)
    }

    @Test
    fun `falls back to first ggml asset when preferred list misses`() {
        val result =
            WhisperModelManager.resolveModel(
                arrayOf("ggml-large-v3-turbo-q5_0.bin")
            )

        assertEquals("large-v3-turbo-q5_0", result?.modelId)
        assertEquals("models/ggml-large-v3-turbo-q5_0.bin", result?.assetPath)
    }

    @Test
    fun `returns null when no model asset exists`() {
        assertNull(WhisperModelManager.resolveModel(arrayOf("README.md")))
    }
}

package org.fcitx.fcitx5.android.input.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WhisperModelManagerTest {
    @Test
    fun `prefers base model family first`() {
        val result =
            WhisperModelManager.resolveModelAssetPath(
                arrayOf("ggml-small.bin", "ggml-base-q5_1.bin", "ggml-tiny.bin")
            )

        assertEquals("models/ggml-base-q5_1.bin", result)
    }

    @Test
    fun `falls back to first ggml asset when preferred list misses`() {
        val result =
            WhisperModelManager.resolveModelAssetPath(
                arrayOf("ggml-large-v3-turbo-q5_0.bin")
            )

        assertEquals("models/ggml-large-v3-turbo-q5_0.bin", result)
    }

    @Test
    fun `returns null when no model asset exists`() {
        assertNull(WhisperModelManager.resolveModelAssetPath(arrayOf("README.md")))
    }
}

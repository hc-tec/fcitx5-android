package org.fcitx.fcitx5.android.input.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceModelCatalogTest {
    @Test
    fun `preferred model id overrides default ordering`() {
        val result =
            VoiceModelCatalog.selectWhisperModel(
                assetNames = arrayOf("ggml-base-q5_1.bin", "ggml-small-q5_1.bin"),
                preferredModelId = "small-q5_1"
            )

        assertEquals("small-q5_1", result?.modelId)
    }

    @Test
    fun `descriptor parsing keeps quantization metadata`() {
        val result = VoiceModelCatalog.descriptorFromWhisperAsset("ggml-base-q5_1.bin")

        assertEquals("base", result?.tier)
        assertTrue(result?.quantized == true)
    }
}

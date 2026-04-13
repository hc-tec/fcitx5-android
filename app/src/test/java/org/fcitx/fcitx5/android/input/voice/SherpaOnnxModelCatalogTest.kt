package org.fcitx.fcitx5.android.input.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SherpaOnnxModelCatalogTest {
    @Test
    fun `hotword preference selects transducer when assets are present`() {
        val descriptor =
            SherpaOnnxModelCatalog.resolveAvailableModel(
                availableAssets =
                    setOf(
                        "models/sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23/encoder-epoch-99-avg-1.int8.onnx",
                        "models/sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23/decoder-epoch-99-avg-1.onnx",
                        "models/sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23/joiner-epoch-99-avg-1.int8.onnx",
                        "models/sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23/tokens.txt",
                        "models/sherpa-onnx-streaming-zipformer-small-ctc-zh-int8-2025-04-01/model.int8.onnx",
                        "models/sherpa-onnx-streaming-zipformer-small-ctc-zh-int8-2025-04-01/tokens.txt"
                    ),
                preference = SherpaOnnxModelPreference.HotwordEnhanced
            )

        assertEquals(SherpaOnnxModelPreference.HotwordEnhanced, descriptor?.preference)
        assertTrue(descriptor?.supportsHotwords == true)
    }

    @Test
    fun `falls back to ctc when hotword model assets are missing`() {
        val descriptor =
            SherpaOnnxModelCatalog.resolveAvailableModel(
                availableAssets =
                    setOf(
                        "models/sherpa-onnx-streaming-zipformer-small-ctc-zh-int8-2025-04-01/model.int8.onnx",
                        "models/sherpa-onnx-streaming-zipformer-small-ctc-zh-int8-2025-04-01/tokens.txt"
                    ),
                preference = SherpaOnnxModelPreference.HotwordEnhanced
            )

        assertEquals(SherpaOnnxModelPreference.FastCtc, descriptor?.preference)
        assertFalse(descriptor?.supportsHotwords == true)
    }

    @Test
    fun `hotword model config enables modified beam search`() {
        val config =
            SherpaOnnxModelCatalog
                .descriptorForPreference(SherpaOnnxModelPreference.HotwordEnhanced)
                .buildConfig()

        assertEquals("modified_beam_search", config.decodingMethod)
        assertEquals("cjkchar", config.modelConfig.modelingUnit)
        assertTrue(config.hotwordsScore > 0f)
        assertTrue(config.modelConfig.transducer.encoder.startsWith("models/"))
    }
}

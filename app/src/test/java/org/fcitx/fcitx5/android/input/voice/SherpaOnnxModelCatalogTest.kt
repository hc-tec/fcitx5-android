package org.fcitx.fcitx5.android.input.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SherpaOnnxModelCatalogTest {
    @Test
    fun `auto preference falls back in mixed hotword fast order`() {
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
                preference = SherpaOnnxModelPreference.Auto
            )

        assertEquals(SherpaOnnxModelPreference.HotwordEnhanced, descriptor?.preference)
    }

    @Test
    fun `mixed zh en preference selects bilingual transducer when assets are present`() {
        val descriptor =
            SherpaOnnxModelCatalog.resolveAvailableModel(
                availableAssets =
                    setOf(
                        "models/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20/encoder-epoch-99-avg-1.int8.onnx",
                        "models/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20/decoder-epoch-99-avg-1.onnx",
                        "models/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20/joiner-epoch-99-avg-1.int8.onnx",
                        "models/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20/tokens.txt",
                        "models/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20/bpe.vocab",
                        "models/sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23/encoder-epoch-99-avg-1.int8.onnx",
                        "models/sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23/decoder-epoch-99-avg-1.onnx",
                        "models/sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23/joiner-epoch-99-avg-1.int8.onnx",
                        "models/sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23/tokens.txt",
                        "models/sherpa-onnx-streaming-zipformer-small-ctc-zh-int8-2025-04-01/model.int8.onnx",
                        "models/sherpa-onnx-streaming-zipformer-small-ctc-zh-int8-2025-04-01/tokens.txt"
                    ),
                preference = SherpaOnnxModelPreference.MixedZhEn
            )

        assertEquals(SherpaOnnxModelPreference.MixedZhEn, descriptor?.preference)
        assertTrue(descriptor?.supportsHotwords == true)
    }

    @Test
    fun `mixed zh en falls back to chinese hotword model when bilingual assets are missing`() {
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
                preference = SherpaOnnxModelPreference.MixedZhEn
            )

        assertEquals(SherpaOnnxModelPreference.HotwordEnhanced, descriptor?.preference)
        assertTrue(descriptor?.supportsHotwords == true)
    }

    @Test
    fun `mixed zh en model config enables modified beam search and bpe vocab`() {
        val config =
            SherpaOnnxModelCatalog
                .descriptorForPreference(SherpaOnnxModelPreference.MixedZhEn)
                .buildConfig()

        assertEquals("modified_beam_search", config.decodingMethod)
        assertEquals("cjkchar+bpe", config.modelConfig.modelingUnit)
        assertTrue(config.modelConfig.bpeVocab.endsWith("/bpe.vocab"))
        assertTrue(config.hotwordsScore > 0f)
        assertTrue(config.modelConfig.transducer.encoder.startsWith("models/"))
    }

    @Test
    fun `hotword preference still resolves chinese transducer`() {
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
    fun `fast ctc remains non hotword fallback`() {
        val descriptor =
            SherpaOnnxModelCatalog.resolveAvailableModel(
                availableAssets =
                    setOf(
                        "models/sherpa-onnx-streaming-zipformer-small-ctc-zh-int8-2025-04-01/model.int8.onnx",
                        "models/sherpa-onnx-streaming-zipformer-small-ctc-zh-int8-2025-04-01/tokens.txt"
                    ),
                preference = SherpaOnnxModelPreference.FastCtc
            )

        assertEquals(SherpaOnnxModelPreference.FastCtc, descriptor?.preference)
        assertFalse(descriptor?.supportsHotwords == true)
    }
}

package org.fcitx.fcitx5.android.input.voice

import androidx.test.platform.app.InstrumentationRegistry
import com.whispercpp.whisper.WhisperContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WhisperInitializationInstrumentationTest {

    @Test
    fun bundledWhisperModel_initializesContextFromAssets() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val assetNames = context.assets.list("models")
        assertNotNull("Expected bundled whisper assets under models/", assetNames)
        assertTrue(
            "Expected bundled ggml-base-q5_1.bin model asset",
            assetNames!!.contains("ggml-base-q5_1.bin")
        )

        val systemInfo = WhisperContext.getSystemInfo()
        assertTrue("Expected whisper system info to be non-empty", systemInfo.isNotBlank())

        val whisperContext = WhisperContext.createContextFromAsset(context.assets, "models/ggml-base-q5_1.bin")
        try {
            assertTrue("Expected whisper context init to succeed", whisperContext.toString().isNotBlank())
        } finally {
            whisperContext.release()
        }
    }
}

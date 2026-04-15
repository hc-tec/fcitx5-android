package org.fcitx.fcitx5.android.input.voice

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class SherpaOnnxInitializationInstrumentationTest {
    @Test
    fun bundledSherpaModelInitializes() =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val state = SherpaOnnxModelManager.awaitReady(context, force = true)
            assertTrue(
                "Expected sherpa-onnx model to initialize, but got ${state::class.simpleName}",
                state is SherpaOnnxModelManager.State.Ready
            )
        }
}

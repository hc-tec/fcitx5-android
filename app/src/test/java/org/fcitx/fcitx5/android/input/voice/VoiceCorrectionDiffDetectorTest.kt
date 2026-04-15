package org.fcitx.fcitx5.android.input.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceCorrectionDiffDetectorTest {
    @Test
    fun `detects latin phrase correction inside committed span`() {
        val result =
            VoiceCorrectionDiffDetector.detect(
                baselineText = "我们刚刚讨论了 Open Cloud 方案",
                committedSpan = VoiceCorrectionDiffDetector.TextSpan(8, 18),
                currentText = "我们刚刚讨论了 OpenClaw 方案"
            )

        requireNotNull(result)
        assertEquals("Open Cloud", result.originalText)
        assertEquals("OpenClaw", result.correctedText)
    }

    @Test
    fun `ignores append after committed span`() {
        val result =
            VoiceCorrectionDiffDetector.detect(
                baselineText = "OpenClaw",
                committedSpan = VoiceCorrectionDiffDetector.TextSpan(0, 9),
                currentText = "OpenClaw today"
            )

        assertNull(result)
    }

    @Test
    fun `ignores unfinished prefix deletion`() {
        val result =
            VoiceCorrectionDiffDetector.detect(
                baselineText = "Open Cloud",
                committedSpan = VoiceCorrectionDiffDetector.TextSpan(0, 10),
                currentText = "Open Clou"
            )

        assertNull(result)
    }

    @Test
    fun `detects han mixed phrase correction`() {
        val result =
            VoiceCorrectionDiffDetector.detect(
                baselineText = "这是 Open Cloud 输入",
                committedSpan = VoiceCorrectionDiffDetector.TextSpan(3, 13),
                currentText = "这是 OpenClaw 输入"
            )

        requireNotNull(result)
        assertEquals("Open Cloud", result.originalText)
        assertEquals("OpenClaw", result.correctedText)
    }
}

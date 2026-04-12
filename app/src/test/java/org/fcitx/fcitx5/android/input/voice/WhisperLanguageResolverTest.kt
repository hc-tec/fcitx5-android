package org.fcitx.fcitx5.android.input.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class WhisperLanguageResolverTest {
    @Test
    fun `maps chinese variants to zh`() {
        assertEquals("zh", WhisperLanguageResolver.resolve("zh-CN"))
        assertEquals("zh", WhisperLanguageResolver.resolve("yue-Hant-HK"))
        assertEquals("zh", WhisperLanguageResolver.resolve("cmn-Hans-CN"))
    }

    @Test
    fun `falls back to english when locale is blank`() {
        assertEquals("en", WhisperLanguageResolver.resolve(""))
        assertEquals("en", WhisperLanguageResolver.resolve("   "))
    }

    @Test
    fun `keeps non chinese language code`() {
        assertEquals("en", WhisperLanguageResolver.resolve("en-US"))
        assertEquals("ja", WhisperLanguageResolver.resolve("ja-JP"))
    }
}

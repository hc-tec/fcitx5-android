package org.fcitx.fcitx5.android.input.voice

import android.content.Context
import android.os.SystemClock
import android.util.Log
import org.fcitx.fcitx5.android.data.prefs.AppPrefs

internal object VoiceInputPrewarmer {
    @Volatile
    private var lastWarmupRequestElapsedMs: Long = 0L

    fun maybePrewarm(context: Context) {
        if (!shouldPrewarm()) {
            return
        }

        val now = SystemClock.elapsedRealtime()
        if (now - lastWarmupRequestElapsedMs < MIN_PREWARM_INTERVAL_MS) {
            return
        }
        lastWarmupRequestElapsedMs = now

        WhisperModelManager.ensureReady(context.applicationContext, force = false) { state ->
            if (state !is WhisperModelManager.State.Loading) {
                Log.i(LOG_TAG, "Background warmup finished state=${state::class.simpleName}")
            }
        }
    }

    private fun shouldPrewarm(): Boolean =
        runCatching {
            val prefs = AppPrefs.getInstance().keyboard
            prefs.showVoiceInputButton.getValue() &&
                prefs.voiceInputMode.getValue() == VoiceInputMode.BuiltInSpeechRecognizer
        }.getOrDefault(false)

    private const val MIN_PREWARM_INTERVAL_MS = 2_500L
    private const val LOG_TAG = "VoicePrewarmer"
}

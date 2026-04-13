package org.fcitx.fcitx5.android.input.voice

import android.content.Context
import android.os.SystemClock
import android.util.Log

internal object VoiceInputPrewarmer {
    @Volatile
    private var lastWarmupRequestElapsedMs: Long = 0L

    fun maybePrewarm(context: Context) {
        if (!VoiceInputLauncher.shouldPrewarm()) {
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

    private const val MIN_PREWARM_INTERVAL_MS = 2_500L
    private const val LOG_TAG = "VoicePrewarmer"
}

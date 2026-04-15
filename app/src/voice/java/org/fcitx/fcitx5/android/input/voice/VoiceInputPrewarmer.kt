package org.fcitx.fcitx5.android.input.voice

import android.content.Context
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Executors

internal object VoiceInputPrewarmer {
    private val executor = Executors.newSingleThreadExecutor()

    @Volatile
    private var lastWarmupRequestElapsedMs: Long = 0L

    @Volatile
    private var warmupQueued = false

    fun maybePrewarm(context: Context) {
        if (!VoiceInputLauncher.shouldPrewarm()) {
            return
        }

        val now = SystemClock.elapsedRealtime()
        if (now - lastWarmupRequestElapsedMs < MIN_PREWARM_INTERVAL_MS) {
            return
        }
        lastWarmupRequestElapsedMs = now

        if (warmupQueued) {
            return
        }
        warmupQueued = true

        val appContext = context.applicationContext
        executor.execute {
            try {
                val engine = VoiceEngineFactory.create(appContext)
                val result = runBlocking { engine.warmup(force = false) }
                Log.i(
                    LOG_TAG,
                    "Background warmup finished engine=${engine.engineId} ready=${result.ready} model=${result.modelId ?: "unknown"}"
                )
            } catch (error: Exception) {
                Log.w(LOG_TAG, "Background warmup failed", error)
            } finally {
                warmupQueued = false
            }
        }
    }

    private const val MIN_PREWARM_INTERVAL_MS = 2_500L
    private const val LOG_TAG = "VoicePrewarmer"
}

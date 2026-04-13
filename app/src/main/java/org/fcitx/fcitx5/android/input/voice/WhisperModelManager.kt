package org.fcitx.fcitx5.android.input.voice

import android.content.Context
import android.util.Log
import com.whispercpp.whisper.WhisperContext
import kotlinx.coroutines.runBlocking
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

internal object WhisperModelManager {
    sealed interface State {
        data object Uninitialized : State

        data object Loading : State

        data class Ready(
            val whisperContext: WhisperContext,
            val model: VoiceModelDescriptor
        ) : State

        data class Error(
            val message: String,
            val modelMissing: Boolean
        ) : State
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val callbacks = mutableListOf<(State) -> Unit>()

    @Volatile
    private var state: State = State.Uninitialized

    @Volatile
    private var loading = false

    @Volatile
    private var forceCpuFallback = false

    @Volatile
    private var discardPreviousReadyContext = false

    fun currentState(): State = state

    fun noteGpuInferenceFailure(reason: String) {
        if (!forceCpuFallback) {
            Log.w(LOG_TAG, "Switching whisper.cpp to CPU fallback after GPU inference failure: $reason")
        }
        forceCpuFallback = true
        discardPreviousReadyContext = true
    }

    suspend fun awaitReady(
        context: Context,
        force: Boolean = false
    ): State =
        suspendCoroutine { continuation ->
            ensureReady(context, force) { nextState ->
                if (nextState !is State.Loading) {
                    continuation.resume(nextState)
                }
            }
        }

    fun ensureReady(
        context: Context,
        force: Boolean = false,
        callback: (State) -> Unit
    ) {
        val immediate: State
        var shouldLoad = false
        var previousReady: State.Ready? = null
        var releasePreviousReady = true

        synchronized(this) {
            val currentState = state
            immediate =
                when {
                    currentState is State.Ready && !force && !shouldReloadForPreference(context, currentState) -> currentState
                    loading && !force -> {
                        callbacks += callback
                        State.Loading
                    }
                    else -> {
                        previousReady = currentState as? State.Ready
                        releasePreviousReady = !discardPreviousReadyContext
                        discardPreviousReadyContext = false
                        callbacks += callback
                        loading = true
                        state = State.Loading
                        shouldLoad = true
                        State.Loading
                    }
                }
        }

        callback(immediate)
        if (!shouldLoad) {
            return
        }

        val appContext = context.applicationContext
        executor.execute {
            previousReady?.let { readyState ->
                if (releasePreviousReady) {
                    runCatching {
                        runBlocking { readyState.whisperContext.release() }
                    }.onFailure { error ->
                        Log.w(LOG_TAG, "Failed to release previous whisper context", error)
                    }
                } else {
                    Log.w(LOG_TAG, "Discarding previous whisper context without release after GPU failure")
                }
            }

            val useGpu = !forceCpuFallback
            Log.i(LOG_TAG, "Loading whisper model force=$force preference=${currentPreference()} useGpu=$useGpu")
            val nextState =
                runCatching { loadContext(appContext, useGpu = useGpu) }
                    .getOrElse { error ->
                        val message =
                            error.message
                                ?.takeIf { it.isNotBlank() }
                                ?: "Failed to initialize whisper.cpp"
                        State.Error(message = message, modelMissing = false)
                    }

            val completionCallbacks: List<(State) -> Unit>
            synchronized(this) {
                state = nextState
                loading = false
                completionCallbacks = callbacks.toList()
                callbacks.clear()
            }
            Log.i(LOG_TAG, "Model manager state=${nextState::class.simpleName}")
            completionCallbacks.forEach { it(nextState) }
        }
    }

    internal fun resolveModel(assetNames: Array<String>): VoiceModelDescriptor? =
        VoiceModelCatalog.selectWhisperModel(
            assetNames = assetNames,
            preference = currentPreference()
        )

    private fun loadContext(
        context: Context,
        useGpu: Boolean
    ): State {
        val assetNames =
            context.assets.list(MODEL_ASSET_DIR)
                ?: emptyArray()
        val model = resolveModel(assetNames)
        if (model == null) {
            return State.Error(
                message = "No whisper.cpp model found under app/src/main/assets/$MODEL_ASSET_DIR/",
                modelMissing = true
            )
        }

        val whisperContext = WhisperContext.createContextFromAsset(context.assets, model.assetPath, useGpu = useGpu)
        Log.i(LOG_TAG, "Loaded model asset=${model.assetPath} modelId=${model.modelId} useGpu=$useGpu")
        return State.Ready(whisperContext = whisperContext, model = model)
    }

    private fun shouldReloadForPreference(
        context: Context,
        state: State.Ready
    ): Boolean {
        val assetNames = context.assets.list(MODEL_ASSET_DIR) ?: return false
        val preferred =
            VoiceModelCatalog.selectWhisperModel(
                assetNames = assetNames,
                preference = currentPreference()
            )
        return preferred != null && preferred.modelId != state.model.modelId
    }

    private fun currentPreference(): VoiceModelPreference =
        runCatching {
            AppPrefs.getInstance().keyboard.builtInVoiceModel.getValue()
        }.getOrDefault(VoiceModelPreference.Balanced)

    private const val MODEL_ASSET_DIR = "models"
    private const val LOG_TAG = "WhisperModelManager"
}

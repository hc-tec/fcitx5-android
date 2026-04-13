package org.fcitx.fcitx5.android.input.voice

import android.content.Context
import com.whispercpp.whisper.WhisperContext
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

    fun currentState(): State = state

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

        synchronized(this) {
            immediate =
                when {
                    state is State.Ready && !force -> state
                    loading && !force -> {
                        callbacks += callback
                        State.Loading
                    }
                    else -> {
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
            val nextState =
                runCatching { loadContext(appContext) }
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
            completionCallbacks.forEach { it(nextState) }
        }
    }

    internal fun resolveModel(assetNames: Array<String>): VoiceModelDescriptor? =
        VoiceModelCatalog.selectWhisperModel(assetNames)

    private fun loadContext(context: Context): State {
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

        val whisperContext = WhisperContext.createContextFromAsset(context.assets, model.assetPath)
        return State.Ready(whisperContext = whisperContext, model = model)
    }

    private const val MODEL_ASSET_DIR = "models"
}

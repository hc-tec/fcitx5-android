package org.fcitx.fcitx5.android.input.voice

import android.content.Context
import com.whispercpp.whisper.WhisperContext
import java.util.concurrent.Executors

internal object WhisperModelManager {
    sealed interface State {
        data object Uninitialized : State

        data object Loading : State

        data class Ready(
            val whisperContext: WhisperContext,
            val assetPath: String
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

    internal fun resolveModelAssetPath(assetNames: Array<String>): String? {
        val normalized =
            assetNames
                .map(String::trim)
                .filter(String::isNotBlank)
                .toSet()

        preferredAssets.firstOrNull { it in normalized }?.let { return "$MODEL_ASSET_DIR/$it" }

        return normalized
            .filter { it.startsWith("ggml-") && it.endsWith(".bin") }
            .sorted()
            .firstOrNull()
            ?.let { "$MODEL_ASSET_DIR/$it" }
    }

    private fun loadContext(context: Context): State {
        val assetNames =
            context.assets.list(MODEL_ASSET_DIR)
                ?: emptyArray()
        val assetPath = resolveModelAssetPath(assetNames)
        if (assetPath == null) {
            return State.Error(
                message = "No whisper.cpp model found under app/src/main/assets/$MODEL_ASSET_DIR/",
                modelMissing = true
            )
        }

        val whisperContext = WhisperContext.createContextFromAsset(context.assets, assetPath)
        return State.Ready(whisperContext = whisperContext, assetPath = assetPath)
    }

    private const val MODEL_ASSET_DIR = "models"

    private val preferredAssets =
        listOf(
            "ggml-base-q5_1.bin",
            "ggml-base-q8_0.bin",
            "ggml-base.bin",
            "ggml-small-q5_1.bin",
            "ggml-small-q8_0.bin",
            "ggml-small.bin",
            "ggml-tiny-q5_1.bin",
            "ggml-tiny-q8_0.bin",
            "ggml-tiny.bin"
        )
}

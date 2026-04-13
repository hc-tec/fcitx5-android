package org.fcitx.fcitx5.android.input.voice

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import com.k2fsa.sherpa.onnx.OnlineCtcFstDecoderConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.getEndpointConfig
import com.k2fsa.sherpa.onnx.getFeatureConfig
import com.k2fsa.sherpa.onnx.getModelConfig
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

internal data class SherpaModelDescriptor(
    val preference: SherpaOnnxModelPreference,
    val modelId: String,
    val assetDir: String,
    val requiredAssets: List<String>,
    val supportsHotwords: Boolean,
    val buildConfig: () -> OnlineRecognizerConfig
)

internal object SherpaOnnxModelCatalog {
    private const val MODEL_ASSET_ROOT = "models"
    private const val FAST_CTC_MODEL_DIR = "sherpa-onnx-streaming-zipformer-small-ctc-zh-int8-2025-04-01"
    private const val HOTWORD_MODEL_DIR = "sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23"

    private val fastCtcModel =
        SherpaModelDescriptor(
            preference = SherpaOnnxModelPreference.FastCtc,
            modelId = FAST_CTC_MODEL_DIR,
            assetDir = "$MODEL_ASSET_ROOT/$FAST_CTC_MODEL_DIR",
            requiredAssets = listOf("$MODEL_ASSET_ROOT/$FAST_CTC_MODEL_DIR/model.int8.onnx", "$MODEL_ASSET_ROOT/$FAST_CTC_MODEL_DIR/tokens.txt"),
            supportsHotwords = false
        ) {
            OnlineRecognizerConfig(
                featConfig = getFeatureConfig(sampleRate = WhisperAudioRecorder.SAMPLE_RATE, featureDim = 80),
                modelConfig = prefixModelConfig(getModelConfig(type = 15)!!, MODEL_ASSET_ROOT),
                ctcFstDecoderConfig = OnlineCtcFstDecoderConfig(),
                endpointConfig = getEndpointConfig(),
                enableEndpoint = true,
                decodingMethod = "greedy_search",
                maxActivePaths = 4
            ).normalized()
        }

    private val hotwordEnhancedModel =
        SherpaModelDescriptor(
            preference = SherpaOnnxModelPreference.HotwordEnhanced,
            modelId = HOTWORD_MODEL_DIR,
            assetDir = "$MODEL_ASSET_ROOT/$HOTWORD_MODEL_DIR",
            requiredAssets =
                listOf(
                    "$MODEL_ASSET_ROOT/$HOTWORD_MODEL_DIR/encoder-epoch-99-avg-1.int8.onnx",
                    "$MODEL_ASSET_ROOT/$HOTWORD_MODEL_DIR/decoder-epoch-99-avg-1.onnx",
                    "$MODEL_ASSET_ROOT/$HOTWORD_MODEL_DIR/joiner-epoch-99-avg-1.int8.onnx",
                    "$MODEL_ASSET_ROOT/$HOTWORD_MODEL_DIR/tokens.txt"
                ),
            supportsHotwords = true
        ) {
            OnlineRecognizerConfig(
                featConfig = getFeatureConfig(sampleRate = WhisperAudioRecorder.SAMPLE_RATE, featureDim = 80),
                modelConfig =
                    prefixModelConfig(getModelConfig(type = 9)!!, MODEL_ASSET_ROOT).apply {
                        modelingUnit = "cjkchar"
                    },
                ctcFstDecoderConfig = OnlineCtcFstDecoderConfig(),
                endpointConfig = getEndpointConfig(),
                enableEndpoint = true,
                decodingMethod = "modified_beam_search",
                maxActivePaths = 4,
                hotwordsScore = 1.5f
            ).normalized()
        }

    private val descriptors = listOf(hotwordEnhancedModel, fastCtcModel)

    val defaultPreference: SherpaOnnxModelPreference = SherpaOnnxModelPreference.HotwordEnhanced

    fun descriptorForPreference(preference: SherpaOnnxModelPreference): SherpaModelDescriptor =
        descriptors.first { it.preference == preference }

    fun resolveBundledModel(
        assetManager: AssetManager,
        preference: SherpaOnnxModelPreference = defaultPreference
    ): SherpaModelDescriptor? {
        val availableAssets = linkedSetOf<String>()
        descriptors.forEach { descriptor ->
            descriptor.requiredAssets.forEach { path ->
                if (assetExists(assetManager, path)) {
                    availableAssets += path
                }
            }
        }
        return resolveAvailableModel(availableAssets = availableAssets, preference = preference)
    }

    internal fun resolveAvailableModel(
        availableAssets: Set<String>,
        preference: SherpaOnnxModelPreference = defaultPreference
    ): SherpaModelDescriptor? =
        preferenceOrder(preference)
            .map(::descriptorForPreference)
            .firstOrNull { descriptor -> descriptor.requiredAssets.all(availableAssets::contains) }

    internal fun preferenceOrder(preference: SherpaOnnxModelPreference): List<SherpaOnnxModelPreference> =
        when (preference) {
            SherpaOnnxModelPreference.HotwordEnhanced ->
                listOf(SherpaOnnxModelPreference.HotwordEnhanced, SherpaOnnxModelPreference.FastCtc)
            SherpaOnnxModelPreference.FastCtc ->
                listOf(SherpaOnnxModelPreference.FastCtc, SherpaOnnxModelPreference.HotwordEnhanced)
        }

    private fun assetExists(
        assetManager: AssetManager,
        path: String
    ): Boolean {
        val directory = path.substringBeforeLast('/', "")
        val fileName = path.substringAfterLast('/')
        val entries = assetManager.list(directory) ?: return false
        return entries.contains(fileName)
    }

    private fun prefixModelConfig(
        config: OnlineModelConfig,
        prefix: String
    ): OnlineModelConfig =
        config.apply {
            transducer =
                transducer.copy(
                    encoder = prefixPath(prefix, transducer.encoder),
                    decoder = prefixPath(prefix, transducer.decoder),
                    joiner = prefixPath(prefix, transducer.joiner)
                )
            paraformer =
                paraformer.copy(
                    encoder = prefixPathList(prefix, paraformer.encoder),
                    decoder = prefixPathList(prefix, paraformer.decoder)
                )
            zipformer2Ctc = zipformer2Ctc.copy(model = prefixPath(prefix, zipformer2Ctc.model))
            neMoCtc = neMoCtc.copy(model = prefixPath(prefix, neMoCtc.model))
            toneCtc = toneCtc.copy(model = prefixPath(prefix, toneCtc.model))
            tokens = prefixPath(prefix, tokens)
            bpeVocab = prefixPath(prefix, bpeVocab)
        }

    private fun OnlineRecognizerConfig.normalized(): OnlineRecognizerConfig =
        apply {
            if (modelConfig.numThreads <= 1) {
                modelConfig.numThreads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
            }
            if (modelConfig.debug) {
                modelConfig.debug = false
            }
            if (modelConfig.provider.isBlank()) {
                modelConfig.provider = "cpu"
            }
        }

    private fun prefixPath(
        prefix: String,
        path: String
    ): String {
        val trimmed = path.trim()
        if (trimmed.isBlank() || trimmed.contains(':') || trimmed.startsWith(prefix)) {
            return trimmed
        }
        return "$prefix/$trimmed"
    }

    private fun prefixPathList(
        prefix: String,
        paths: String
    ): String =
        paths
            .split(',')
            .map(String::trim)
            .filter(String::isNotEmpty)
            .joinToString(",") { prefixPath(prefix, it) }
}

internal object SherpaOnnxModelManager {
    sealed interface State {
        data object Uninitialized : State

        data object Loading : State

        data class Ready(
            val recognizer: OnlineRecognizer,
            val model: SherpaModelDescriptor
        ) : State

        data class Error(
            val message: String,
            val modelMissing: Boolean
        ) : State
    }

    private data class LoadConfig(
        val configuredPreference: SherpaOnnxModelPreference
    )

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
        var previousReady: State.Ready? = null

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
                runCatching { readyState.recognizer.release() }
                    .onFailure { error -> Log.w(LOG_TAG, "Failed to release previous sherpa recognizer", error) }
            }

            val loadConfig = currentLoadConfig()
            val nextState =
                runCatching { loadRecognizer(appContext, loadConfig) }
                    .getOrElse { error ->
                        val message =
                            error.message
                                ?.takeIf { it.isNotBlank() }
                                ?: "Failed to initialize sherpa-onnx"
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

    private fun loadRecognizer(
        context: Context,
        loadConfig: LoadConfig
    ): State {
        val model =
            SherpaOnnxModelCatalog.resolveBundledModel(
                assetManager = context.assets,
                preference = loadConfig.configuredPreference
            )
        if (model == null) {
            return State.Error(
                message = "No sherpa-onnx model found under app/src/main/assets/models/",
                modelMissing = true
            )
        }

        val fellBack = model.preference != loadConfig.configuredPreference
        Log.i(
            LOG_TAG,
            "Loading sherpa-onnx model modelId=${model.modelId} configured=${loadConfig.configuredPreference} effective=${model.preference} fallback=$fellBack"
        )
        val recognizer = OnlineRecognizer(assetManager = context.assets, config = model.buildConfig())
        return State.Ready(recognizer = recognizer, model = model)
    }

    private fun shouldReloadForPreference(
        context: Context,
        state: State.Ready
    ): Boolean {
        val loadConfig = currentLoadConfig()
        val preferred =
            SherpaOnnxModelCatalog.resolveBundledModel(
                assetManager = context.assets,
                preference = loadConfig.configuredPreference
            ) ?: return false
        return preferred.modelId != state.model.modelId
    }

    private fun currentLoadConfig(): LoadConfig =
        LoadConfig(configuredPreference = currentPreference())

    private fun currentPreference(): SherpaOnnxModelPreference =
        runCatching {
            AppPrefs.getInstance().keyboard.builtInSherpaModel.getValue()
        }.getOrDefault(SherpaOnnxModelCatalog.defaultPreference)

    private const val LOG_TAG = "SherpaModelManager"
}

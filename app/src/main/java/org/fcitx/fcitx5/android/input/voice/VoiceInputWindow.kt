package org.fcitx.fcitx5.android.input.voice

import android.util.Log
import android.view.MotionEvent
import android.view.View
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.dependency.theme
import org.fcitx.fcitx5.android.input.wm.InputWindow
import org.fcitx.fcitx5.android.voice.core.VoiceRecognitionResult

internal class VoiceInputWindow : InputWindow.ExtendedInputWindow<VoiceInputWindow>() {
    private val service: FcitxInputMethodService by manager.inputMethodService()
    private val theme by manager.theme()
    private val ui by lazy { VoiceInputUi(context, theme) }

    private var sessionId: String = ""
    private var latestTranscript: String = ""
    private var listening = false
    private var processing = false
    private var recorder: WhisperAudioRecorder? = null
    private var voiceEngine: VoiceEngine? = null
    private var engineLoading = false
    private var engineReady = false
    private var engineModelMissing = false
    private var engineErrorMessage: String? = null
    private var windowScope = createWindowScope()
    private var transcribeJob: Job? = null
    private var endpointLoopJob: Job? = null
    private var partialLoopJob: Job? = null
    private var partialTranscriptionJob: Job? = null
    private var lastPartialScheduledSamples = 0
    private var listeningState = VoiceListeningState.NOT_TALKED_YET
    private val partialStabilizer = VoicePartialStabilizer()

    override val title: String by lazy {
        context.getString(R.string.voice_input_title)
    }

    override fun onCreateView(): View {
        ui.setHoldTouchListener(holdTouchListener)
        return ui.root
    }

    override fun onAttached() {
        if (!windowScope.isActive) {
            windowScope = createWindowScope()
        }
        if (voiceEngine == null) {
            voiceEngine = VoiceEngineFactory.create(context)
        }
        ensureSession()
        ensureEngineReady()
        renderIdle()
    }

    override fun onDetached() {
        transcribeJob?.cancel()
        transcribeJob = null
        endpointLoopJob?.cancel()
        endpointLoopJob = null
        partialLoopJob?.cancel()
        partialLoopJob = null
        partialTranscriptionJob?.cancel()
        partialTranscriptionJob = null
        recorder?.cancel()
        recorder = null
        listening = false
        processing = false
        engineLoading = false
        windowScope.cancel()
        if (sessionId.isNotBlank()) {
            VoiceInputRuntime.sessionManager.clearSession(sessionId)
            sessionId = ""
        }
    }

    private val holdTouchListener =
        View.OnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startListening()
                    true
                }
                MotionEvent.ACTION_UP -> {
                    stopListening()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    cancelListening()
                    true
                }
                else -> false
            }
        }

    private fun startListening() {
        if (listening || processing) {
            Log.i(LOG_TAG, "Ignoring start request listening=$listening processing=$processing")
            return
        }

        ensureSession()
        val request = VoiceInputContextReader.capture(service)
        VoiceInputRuntime.sessionManager.updateSession(sessionId, request)

        if (!VoiceInputPermission.hasRecordAudioPermission(context)) {
            ui.renderPermissionRequired(
                View.OnClickListener {
                    VoiceInputPermission.launchPermissionActivity(context)
                }
            )
            return
        }

        when {
            engineLoading -> {
                Log.i(LOG_TAG, "Engine still loading, rendering loading state")
                ui.renderLoading()
                return
            }
            !engineReady -> {
                Log.i(LOG_TAG, "Engine not ready yet, retrying warmup")
                ensureEngineReady(force = engineErrorMessage != null && !engineModelMissing)
                renderIdle()
                return
            }
        }

        val whisperRecorder =
            runCatching { WhisperAudioRecorder().also { it.start() } }
                .getOrElse { error ->
                    recorder = null
                    Log.w(LOG_TAG, "Failed to start recorder", error)
                    ui.renderMessage(
                        context.getString(
                            R.string.voice_input_error,
                            error.message ?: context.getString(R.string.voice_input_engine_generic_error)
                        ),
                        latestTranscript
                    )
                    return
                }

        service.finishComposing()
        transcribeJob?.cancel()
        endpointLoopJob?.cancel()
        partialLoopJob?.cancel()
        partialTranscriptionJob?.cancel()
        recorder = whisperRecorder
        latestTranscript = ""
        listening = true
        processing = false
        listeningState = VoiceListeningState.NOT_TALKED_YET
        lastPartialScheduledSamples = 0
        partialStabilizer.reset()
        Log.i(LOG_TAG, "Listening started session=$sessionId locale=${request.locale}")
        ui.renderListening(latestTranscript, listeningState)
        startEndpointLoop()
        startPartialLoop()
    }

    private fun stopListening() {
        if (!listening) {
            return
        }

        val activeRecorder = recorder ?: return
        recorder = null
        listening = false
        processing = true
        endpointLoopJob?.cancel()
        endpointLoopJob = null
        partialLoopJob?.cancel()
        partialLoopJob = null
        partialTranscriptionJob?.cancel()

        val request = VoiceInputContextReader.capture(service)
        VoiceInputRuntime.sessionManager.updateSession(sessionId, request)
        ui.renderProcessing(latestTranscript)

        transcribeJob?.cancel()
        transcribeJob =
            windowScope.launch {
                try {
                    val audioData = withContext(Dispatchers.IO) { activeRecorder.stop() }
                    if (audioData.isEmpty()) {
                        processing = false
                        latestTranscript = ""
                        Log.i(LOG_TAG, "Final audio empty, no speech detected")
                        ui.renderMessage(context.getString(R.string.voice_input_no_speech), latestTranscript)
                        return@launch
                    }

                    val activityStats = VoiceActivityDetector.analyze(audioData)
                    if (!activityStats.hasSpeech && latestTranscript.isBlank()) {
                        processing = false
                        Log.i(
                            LOG_TAG,
                            "Final VAD rejected audio samples=${audioData.size} speechRatio=${activityStats.speechRatio} trailingSilenceMs=${activityStats.trailingSilenceMs}"
                        )
                        ui.renderMessage(context.getString(R.string.voice_input_no_speech), latestTranscript)
                        return@launch
                    }

                    val engine = voiceEngine ?: return@launch
                    Log.i(
                        LOG_TAG,
                        "Submitting final transcription samples=${audioData.size} locale=${request.locale} speechRatio=${activityStats.speechRatio}"
                    )
                    val result = engine.transcribeFinal(audioData, request.locale)
                    commitTranscript(result.text)
                } catch (_: CancellationException) {
                } catch (error: Exception) {
                    processing = false
                    Log.w(LOG_TAG, "Final transcription failed", error)
                    ui.renderMessage(
                        context.getString(
                            R.string.voice_input_error,
                            error.message ?: context.getString(R.string.voice_input_engine_generic_error)
                        ),
                        latestTranscript
                    )
                }
            }
    }

    private fun cancelListening() {
        transcribeJob?.cancel()
        transcribeJob = null
        endpointLoopJob?.cancel()
        endpointLoopJob = null
        partialLoopJob?.cancel()
        partialLoopJob = null
        partialTranscriptionJob?.cancel()
        partialTranscriptionJob = null
        recorder?.cancel()
        recorder = null
        listening = false
        processing = false
        listeningState = VoiceListeningState.NOT_TALKED_YET
        partialStabilizer.reset()
        Log.i(LOG_TAG, "Listening cancelled session=$sessionId")
        ui.renderReady(latestTranscript)
    }

    private fun startEndpointLoop() {
        endpointLoopJob =
            windowScope.launch {
                while (listening) {
                    val activeRecorder = recorder ?: break
                    val pcmSnapshot =
                        withContext(Dispatchers.Default) {
                            activeRecorder.snapshotPcm16(REALTIME_ANALYSIS_MAX_SAMPLES)
                        }
                    if (pcmSnapshot.isNotEmpty()) {
                        val endpointDecision = analyzeEndpoint(pcmSnapshot)
                        if (endpointDecision.state != listeningState) {
                            listeningState = endpointDecision.state
                            ui.renderListening(latestTranscript, listeningState)
                        }

                        if (endpointDecision.shouldAutoStop && listening) {
                            Log.i(
                                LOG_TAG,
                                "Auto-stopping from endpoint detector source=${endpointDecision.source} trailingSilenceMs=${endpointDecision.trailingSilenceMs}"
                            )
                            stopListening()
                            break
                        }
                    }
                    delay(ENDPOINT_POLL_INTERVAL_MS)
                }
            }
    }

    private fun startPartialLoop() {
        val engine = voiceEngine ?: return
        if (!engine.capabilities.supportsStreamingPartial) {
            return
        }

        partialLoopJob =
            windowScope.launch {
                delay(engine.capabilities.partialInitialDelayMs)
                while (listening) {
                    val activeRecorder = recorder ?: break
                    val sampleCount = activeRecorder.sampleCount()
                    val canSchedule =
                        sampleCount >= engine.capabilities.partialMinSamples &&
                            sampleCount - lastPartialScheduledSamples >= engine.capabilities.partialStepSamples &&
                            partialTranscriptionJob?.isActive != true

                    if (canSchedule) {
                        lastPartialScheduledSamples = sampleCount
                        val request = VoiceInputContextReader.capture(service)
                        VoiceInputRuntime.sessionManager.updateSession(sessionId, request)
                        val snapshot =
                            withContext(Dispatchers.Default) {
                                activeRecorder.snapshot(engine.capabilities.partialMaxSamples)
                            }
                        val pcmSnapshot =
                            withContext(Dispatchers.Default) {
                                activeRecorder.snapshotPcm16(engine.capabilities.partialMaxSamples)
                            }
                        if (snapshot.isNotEmpty()) {
                            val endpointDecision = analyzeEndpoint(pcmSnapshot, snapshot)
                            if (!endpointDecision.hasSpeech) {
                                Log.i(
                                    LOG_TAG,
                                    "Skipping partial: VAD rejected snapshot samples=${snapshot.size} source=${endpointDecision.source}"
                                )
                                delay(engine.capabilities.partialPollIntervalMs)
                                continue
                            }
                            if (endpointDecision.trailingSilenceMs >= 1200 && latestTranscript.isNotBlank()) {
                                Log.i(
                                    LOG_TAG,
                                    "Skipping partial: trailingSilenceMs=${endpointDecision.trailingSilenceMs} transcriptLength=${latestTranscript.length}"
                                )
                                delay(engine.capabilities.partialPollIntervalMs)
                                continue
                            }
                            Log.i(
                                LOG_TAG,
                                "Scheduling partial samples=${snapshot.size} locale=${request.locale} source=${endpointDecision.source}"
                            )
                            schedulePartialTranscription(snapshot, request.locale)
                        }
                    }
                    delay(engine.capabilities.partialPollIntervalMs)
                }
            }
    }

    private fun schedulePartialTranscription(
        audioData: FloatArray,
        localeTag: String
    ) {
        val engine = voiceEngine ?: return
        val job =
            windowScope.launch {
                try {
                    val result = engine.transcribePartial(audioData, localeTag)
                    if (!listening) {
                        return@launch
                    }

                    val rawText = normalizeTranscript(result.text)
                    if (rawText.isBlank()) {
                        return@launch
                    }

                    val processed =
                        VoiceInputRuntime.sessionManager.processPartial(
                            sessionId,
                            VoiceRecognitionResult(text = rawText, confidence = 0.55)
                        )
                    val processedText = processed?.text?.ifBlank { rawText } ?: rawText
                    latestTranscript =
                        partialStabilizer.update(
                            candidate = processedText,
                            stableLength = processed?.stableLength ?: 0
                        )
                    Log.i(
                        LOG_TAG,
                        "Partial updated rawLength=${rawText.length} renderedLength=${latestTranscript.length}"
                    )
                    if (listening) {
                        ui.renderListening(latestTranscript, listeningState)
                    }
                } catch (_: CancellationException) {
                } catch (error: Exception) {
                    Log.w(LOG_TAG, "Ignoring partial transcription failure", error)
                } finally {
                    if (partialTranscriptionJob === coroutineContext[Job]) {
                        partialTranscriptionJob = null
                    }
                }
            }
        partialTranscriptionJob = job
    }

    private fun commitTranscript(rawText: String) {
        listening = false
        processing = false
        partialStabilizer.reset()

        val transcript = normalizeTranscript(rawText)
        val processed =
            VoiceInputRuntime.sessionManager.processFinal(
                sessionId,
                VoiceRecognitionResult(text = transcript, confidence = if (transcript.isBlank()) 0.0 else 0.75)
            )
        val committedText =
            processed?.text?.takeIf { it.isNotBlank() }
                ?: transcript
        latestTranscript = committedText

        if (committedText.isBlank()) {
            Log.i(LOG_TAG, "Final transcript blank after post-processing")
            ui.renderMessage(context.getString(R.string.voice_input_no_speech), latestTranscript)
            return
        }

        Log.i(LOG_TAG, "Committing final transcript length=${committedText.length}")
        service.commitText(committedText)
        ui.renderReady(latestTranscript)
    }

    private fun analyzeEndpoint(
        pcmSnapshot: ShortArray,
        floatSnapshot: FloatArray? = null
    ): VoiceEndpointDecision {
        val webRtcStats = VoiceWebRtcVad.analyze(pcmSnapshot)
        if (webRtcStats != null) {
            return VoiceEndpointPolicy.decide(
                stats = webRtcStats,
                sampleCount = pcmSnapshot.size,
                source = "webrtc-vad"
            )
        }

        val fallbackSamples = floatSnapshot ?: pcmSnapshot.map { it / 32768f }.toFloatArray()
        val fallbackStats = VoiceActivityDetector.analyze(fallbackSamples)
        return VoiceEndpointPolicy.decide(
            stats = fallbackStats,
            sampleCount = fallbackSamples.size,
            source = "energy-vad"
        )
    }

    private fun renderIdle() {
        when {
            !VoiceInputPermission.hasRecordAudioPermission(context) ->
                ui.renderPermissionRequired(
                    View.OnClickListener {
                        VoiceInputPermission.launchPermissionActivity(context)
                    }
                )
            listening -> ui.renderListening(latestTranscript, listeningState)
            processing -> ui.renderProcessing(latestTranscript)
            engineLoading -> ui.renderLoading()
            engineModelMissing ->
                ui.renderUnavailable(
                    status = context.getString(R.string.voice_input_model_missing),
                    hint = context.getString(R.string.voice_input_model_missing_hint)
                )
            engineErrorMessage != null ->
                ui.renderUnavailable(
                    status = context.getString(R.string.voice_input_engine_error, engineErrorMessage),
                    hint = engineErrorMessage.orEmpty()
                )
            engineReady -> ui.renderReady(latestTranscript)
            else ->
                ui.renderUnavailable(
                    status = context.getString(R.string.voice_input_loading_engine),
                    hint = context.getString(R.string.voice_input_loading_engine_hint)
                )
        }
    }

    private fun ensureSession() {
        if (sessionId.isBlank()) {
            sessionId = VoiceInputRuntime.sessionManager.createSession(VoiceInputContextReader.capture(service))
        }
    }

    private fun ensureEngineReady(force: Boolean = false) {
        if (engineLoading && !force) {
            return
        }
        val engine = voiceEngine ?: VoiceEngineFactory.create(context).also { voiceEngine = it }
        engineLoading = true
        engineErrorMessage = null
        engineModelMissing = false
        Log.i(LOG_TAG, "Ensuring engine ready force=$force")
        renderIdle()

        windowScope.launch {
            try {
                engine.warmup(force)
                val state = WhisperModelManager.currentState()
                engineReady = state is WhisperModelManager.State.Ready
                engineModelMissing = false
                engineErrorMessage = null
                Log.i(LOG_TAG, "Engine ready model=${(state as? WhisperModelManager.State.Ready)?.model?.modelId}")
            } catch (error: Exception) {
                val state = WhisperModelManager.currentState()
                engineReady = false
                engineModelMissing = (state as? WhisperModelManager.State.Error)?.modelMissing == true
                engineErrorMessage =
                    (state as? WhisperModelManager.State.Error)?.message
                        ?: error.message
                        ?: context.getString(R.string.voice_input_engine_generic_error)
                Log.w(LOG_TAG, "Engine warmup failed", error)
            } finally {
                engineLoading = false
                renderIdle()
            }
        }
    }

    private fun normalizeTranscript(value: String): String = value.trim().replace("\\s+".toRegex(), " ")

    private fun createWindowScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private companion object {
        private const val ENDPOINT_POLL_INTERVAL_MS = 150L
        private const val REALTIME_ANALYSIS_MAX_SAMPLES = WhisperAudioRecorder.SAMPLE_RATE * 8
        private const val LOG_TAG = "VoiceInputWindow"
    }
}

package org.fcitx.fcitx5.android.input.voice

import android.util.Log
import android.view.inputmethod.EditorInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.CapabilityFlag
import org.fcitx.fcitx5.android.core.CapabilityFlags
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcastReceiver
import org.fcitx.fcitx5.android.input.dependency.context
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.voice.core.VoiceRecognitionResult
import org.mechdancer.dependency.Dependent
import org.mechdancer.dependency.UniqueComponent
import org.mechdancer.dependency.manager.ManagedHandler
import org.mechdancer.dependency.manager.managedHandler
import org.mechdancer.dependency.manager.must

internal class VoiceInlineSessionController :
    UniqueComponent<VoiceInlineSessionController>(),
    Dependent,
    ManagedHandler by managedHandler(),
    InputBroadcastReceiver {

    private val context by manager.context()
    private val service by manager.inputMethodService()
    private val inlineBar: VoiceInlineBarComponent by manager.must()

    private val keyboardPrefs = AppPrefs.getInstance().keyboard

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
    private var controllerScope = createControllerScope()
    private var transcribeJob: Job? = null
    private var endpointLoopJob: Job? = null
    private var partialLoopJob: Job? = null
    private var partialTranscriptionJob: Job? = null
    private var hideBarJob: Job? = null
    private var lastPartialScheduledSamples = 0
    private var lastIncrementalEngineSamples = 0
    private var pendingIncrementalTargetSamples = 0
    private var listeningState = VoiceListeningState.NOT_TALKED_YET
    private val partialStabilizer = VoicePartialStabilizer()

    override fun onStartInput(info: EditorInfo, capFlags: CapabilityFlags) {
        cancelActiveSession(hideBar = true)
        clearCurrentSession()
        latestTranscript = ""
        if (VoiceInputLauncher.shouldPrewarm()) {
            ensureEngineReady(showUi = false)
        } else {
            inlineBar.hide()
        }
    }

    fun startHoldToTalk(): Boolean {
        if (!controllerScope.isActive) {
            controllerScope = createControllerScope()
        }
        cancelHideBar()

        if (keyboardPrefs.voiceInputMode.getValue() != VoiceInputMode.BuiltInSpeechRecognizer) {
            return false
        }
        if (isPasswordField()) {
            return false
        }
        if (listening || processing) {
            Log.i(LOG_TAG, "Ignoring inline voice start listening=$listening processing=$processing")
            return false
        }

        ensureSession()
        val request = VoiceInputContextReader.capture(service)
        VoiceInputRuntime.sessionManager.updateSession(sessionId, request)

        if (!VoiceInputPermission.hasRecordAudioPermission(context)) {
            inlineBar.show(
                context.getString(R.string.voice_input_permission_message),
                VoiceInlineBarComponent.Tone.Error
            ) {
                VoiceInputPermission.launchPermissionActivity(context)
            }
            return false
        }

        when {
            engineLoading -> {
                inlineBar.show(
                    context.getString(R.string.voice_input_loading_engine),
                    VoiceInlineBarComponent.Tone.Neutral
                )
                return false
            }
            !engineReady -> {
                ensureEngineReady(
                    force = engineErrorMessage != null && !engineModelMissing,
                    showUi = true
                )
                inlineBar.show(
                    context.getString(R.string.voice_input_loading_engine),
                    VoiceInlineBarComponent.Tone.Neutral
                )
                return false
            }
        }

        val activeRecorder =
            runCatching { WhisperAudioRecorder().also { it.start() } }
                .getOrElse { error ->
                    recorder = null
                    Log.w(LOG_TAG, "Failed to start inline recorder", error)
                    showTransientMessage(
                        context.getString(
                            R.string.voice_input_error,
                            error.message ?: context.getString(R.string.voice_input_engine_generic_error)
                        ),
                        tone = VoiceInlineBarComponent.Tone.Error
                    )
                    return false
                }

        service.finishComposing()
        transcribeJob?.cancel()
        endpointLoopJob?.cancel()
        partialLoopJob?.cancel()
        partialTranscriptionJob?.cancel()
        val engine = voiceEngine ?: return false
        runCatching {
            engine.beginSession(request)
        }.onFailure { error ->
            activeRecorder.cancel()
            recorder = null
            Log.w(LOG_TAG, "Failed to begin inline voice session", error)
            showTransientMessage(
                context.getString(
                    R.string.voice_input_error,
                    error.message ?: context.getString(R.string.voice_input_engine_generic_error)
                ),
                tone = VoiceInlineBarComponent.Tone.Error
            )
            return false
        }

        recorder = activeRecorder
        latestTranscript = ""
        listening = true
        processing = false
        listeningState = VoiceListeningState.NOT_TALKED_YET
        lastPartialScheduledSamples = 0
        lastIncrementalEngineSamples = 0
        pendingIncrementalTargetSamples = 0
        partialStabilizer.reset()
        Log.i(LOG_TAG, "Inline voice listening started session=$sessionId locale=${request.locale}")
        renderListeningState()
        startEndpointLoop()
        startPartialLoop()
        return true
    }

    fun stopHoldToTalk() {
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
        val pendingPartialJob = partialTranscriptionJob
        partialTranscriptionJob = null

        val request = VoiceInputContextReader.capture(service)
        VoiceInputRuntime.sessionManager.updateSession(sessionId, request)
        inlineBar.show(
            context.getString(R.string.voice_input_processing),
            VoiceInlineBarComponent.Tone.Neutral
        )

        transcribeJob?.cancel()
        transcribeJob =
            controllerScope.launch {
                try {
                    pendingPartialJob?.cancelAndJoin()
                    val audioData = withContext(Dispatchers.IO) { activeRecorder.stop() }
                    if (audioData.isEmpty()) {
                        voiceEngine?.endSession(cancelled = false)
                        processing = false
                        latestTranscript = ""
                        Log.i(LOG_TAG, "Inline final audio empty, no speech detected")
                        showTransientMessage(context.getString(R.string.voice_input_no_speech))
                        return@launch
                    }

                    val activityStats = VoiceActivityDetector.analyze(audioData)
                    if (!activityStats.hasSpeech && latestTranscript.isBlank()) {
                        voiceEngine?.endSession(cancelled = false)
                        processing = false
                        Log.i(
                            LOG_TAG,
                            "Inline final VAD rejected audio samples=${audioData.size} speechRatio=${activityStats.speechRatio}"
                        )
                        showTransientMessage(context.getString(R.string.voice_input_no_speech))
                        return@launch
                    }

                    val engine = voiceEngine ?: return@launch
                    val finalPayload =
                        if (engine.capabilities.partialAudioMode == VoicePartialAudioMode.IncrementalSession) {
                            audioData.sliceRemaining(lastIncrementalEngineSamples)
                        } else {
                            audioData
                        }
                    Log.i(
                        LOG_TAG,
                        "Submitting inline final transcription samples=${finalPayload.size} totalSamples=${audioData.size} locale=${request.locale}"
                    )
                    val result = engine.transcribeFinal(finalPayload, request.locale)
                    engine.endSession(cancelled = false)
                    commitTranscript(result.text)
                } catch (_: CancellationException) {
                } catch (error: Exception) {
                    voiceEngine?.endSession(cancelled = false)
                    processing = false
                    Log.w(LOG_TAG, "Inline final transcription failed", error)
                    showTransientMessage(
                        context.getString(
                            R.string.voice_input_error,
                            error.message ?: context.getString(R.string.voice_input_engine_generic_error)
                        ),
                        tone = VoiceInlineBarComponent.Tone.Error
                    )
                }
            }
    }

    fun shutdown() {
        cancelActiveSession(hideBar = true)
        clearCurrentSession()
        controllerScope.cancel()
    }

    private fun cancelActiveSession(hideBar: Boolean) {
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
        voiceEngine?.endSession(cancelled = true)
        listening = false
        processing = false
        lastPartialScheduledSamples = 0
        lastIncrementalEngineSamples = 0
        pendingIncrementalTargetSamples = 0
        listeningState = VoiceListeningState.NOT_TALKED_YET
        partialStabilizer.reset()
        cancelHideBar()
        if (hideBar) {
            inlineBar.hide()
        }
    }

    private fun clearCurrentSession() {
        if (sessionId.isNotBlank()) {
            VoiceInputRuntime.sessionManager.clearSession(sessionId)
            sessionId = ""
        }
    }

    private fun startEndpointLoop() {
        endpointLoopJob =
            controllerScope.launch {
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
                            renderListeningState()
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
            controllerScope.launch {
                delay(engine.capabilities.partialInitialDelayMs)
                while (listening) {
                    val activeRecorder = recorder ?: break
                    val sampleCount = activeRecorder.sampleCount()
                    val scheduleCursor =
                        if (engine.capabilities.partialAudioMode == VoicePartialAudioMode.IncrementalSession) {
                            maxOf(lastIncrementalEngineSamples, pendingIncrementalTargetSamples)
                        } else {
                            lastPartialScheduledSamples
                        }
                    val canSchedule =
                        sampleCount >= engine.capabilities.partialMinSamples &&
                            sampleCount - scheduleCursor >= engine.capabilities.partialStepSamples &&
                            partialTranscriptionJob?.isActive != true

                    if (canSchedule) {
                        val request = VoiceInputContextReader.capture(service)
                        VoiceInputRuntime.sessionManager.updateSession(sessionId, request)
                        if (engine.capabilities.partialAudioMode == VoicePartialAudioMode.IncrementalSession) {
                            val targetSampleCount = sampleCount
                            val snapshot =
                                withContext(Dispatchers.Default) {
                                    activeRecorder.snapshotFrom(
                                        startSample = lastIncrementalEngineSamples,
                                        maxSamples = targetSampleCount - lastIncrementalEngineSamples
                                    )
                                }
                            if (snapshot.isNotEmpty()) {
                                pendingIncrementalTargetSamples = targetSampleCount
                                Log.i(
                                    LOG_TAG,
                                    "Scheduling inline incremental partial chunkSamples=${snapshot.size} totalSamples=$targetSampleCount locale=${request.locale}"
                                )
                                schedulePartialTranscription(
                                    audioData = snapshot,
                                    localeTag = request.locale,
                                    incrementalTargetSamples = targetSampleCount
                                )
                            }
                        } else {
                            lastPartialScheduledSamples = sampleCount
                            val analysisMaxSamples = minOf(sampleCount, REALTIME_ANALYSIS_MAX_SAMPLES)
                            val analysisSnapshot =
                                withContext(Dispatchers.Default) {
                                    activeRecorder.snapshot(analysisMaxSamples)
                                }
                            val pcmSnapshot =
                                withContext(Dispatchers.Default) {
                                    activeRecorder.snapshotPcm16(analysisMaxSamples)
                                }
                            val partialSnapshotMaxSamples =
                                if (engine.capabilities.partialAudioMode == VoicePartialAudioMode.FullSession) {
                                    sampleCount
                                } else {
                                    engine.capabilities.partialMaxSamples
                                }
                            val snapshot =
                                withContext(Dispatchers.Default) {
                                    activeRecorder.snapshot(partialSnapshotMaxSamples)
                                }
                            if (snapshot.isNotEmpty()) {
                                val endpointDecision = analyzeEndpoint(pcmSnapshot, analysisSnapshot)
                                if (!endpointDecision.hasSpeech) {
                                    delay(engine.capabilities.partialPollIntervalMs)
                                    continue
                                }
                                if (endpointDecision.trailingSilenceMs >= 1200 && latestTranscript.isNotBlank()) {
                                    delay(engine.capabilities.partialPollIntervalMs)
                                    continue
                                }
                                Log.i(
                                    LOG_TAG,
                                    "Scheduling inline partial samples=${snapshot.size} locale=${request.locale} source=${endpointDecision.source}"
                                )
                                schedulePartialTranscription(
                                    audioData = snapshot,
                                    localeTag = request.locale
                                )
                            }
                        }
                    }
                    delay(engine.capabilities.partialPollIntervalMs)
                }
            }
    }

    private fun schedulePartialTranscription(
        audioData: FloatArray,
        localeTag: String,
        incrementalTargetSamples: Int? = null
    ) {
        val engine = voiceEngine ?: return
        val job =
            controllerScope.launch {
                var consumed = false
                try {
                    val result = engine.transcribePartial(audioData, localeTag)
                    consumed = true
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
                        "Inline partial updated rawLength=${rawText.length} renderedLength=${latestTranscript.length}"
                    )
                    if (listening) {
                        renderListeningState()
                    }
                } catch (_: CancellationException) {
                } catch (error: Exception) {
                    Log.w(LOG_TAG, "Ignoring inline partial transcription failure", error)
                } finally {
                    if (incrementalTargetSamples != null) {
                        if (consumed) {
                            lastIncrementalEngineSamples = maxOf(lastIncrementalEngineSamples, incrementalTargetSamples)
                        }
                        if (pendingIncrementalTargetSamples == incrementalTargetSamples) {
                            pendingIncrementalTargetSamples = 0
                        }
                    }
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
        val committedText = processed?.text?.takeIf { it.isNotBlank() } ?: transcript
        latestTranscript = committedText

        if (committedText.isBlank()) {
            Log.i(LOG_TAG, "Inline final transcript blank after post-processing")
            showTransientMessage(context.getString(R.string.voice_input_no_speech))
            return
        }

        Log.i(LOG_TAG, "Committing inline final transcript length=${committedText.length}")
        service.commitText(committedText)
        showTransientMessage(committedText, durationMs = FINAL_CONFIRM_DURATION_MS)
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

    private fun ensureSession() {
        if (sessionId.isBlank()) {
            sessionId = VoiceInputRuntime.sessionManager.createSession(VoiceInputContextReader.capture(service))
        }
    }

    private fun ensureEngineReady(
        force: Boolean = false,
        showUi: Boolean = false
    ) {
        if (keyboardPrefs.voiceInputMode.getValue() != VoiceInputMode.BuiltInSpeechRecognizer) {
            return
        }
        if (engineLoading && !force) {
            return
        }
        val engine = voiceEngine ?: VoiceEngineFactory.create(context).also { voiceEngine = it }
        engineLoading = true
        engineErrorMessage = null
        engineModelMissing = false
        if (showUi) {
            inlineBar.show(
                context.getString(R.string.voice_input_loading_engine),
                VoiceInlineBarComponent.Tone.Neutral
            )
        }
        Log.i(LOG_TAG, "Ensuring inline voice engine ready force=$force")

        controllerScope.launch {
            try {
                val result = engine.warmup(force)
                engineReady = result.ready
                engineModelMissing = result.modelMissing
                engineErrorMessage = result.message
                if (result.ready) {
                    Log.i(LOG_TAG, "Inline voice engine ready engine=${engine.engineId} model=${result.modelId ?: "unknown"}")
                    if (showUi && !listening && !processing) {
                        inlineBar.hide()
                    }
                } else if (showUi) {
                    val message =
                        if (result.modelMissing) {
                            context.getString(R.string.voice_input_model_missing)
                        } else {
                            context.getString(
                                R.string.voice_input_engine_error,
                                result.message ?: context.getString(R.string.voice_input_engine_generic_error)
                            )
                        }
                    showTransientMessage(message, tone = VoiceInlineBarComponent.Tone.Error)
                }
            } catch (error: Exception) {
                engineReady = false
                engineModelMissing = false
                engineErrorMessage = error.message ?: context.getString(R.string.voice_input_engine_generic_error)
                Log.w(LOG_TAG, "Inline voice engine warmup failed", error)
                if (showUi) {
                    showTransientMessage(
                        context.getString(
                            R.string.voice_input_engine_error,
                            engineErrorMessage ?: context.getString(R.string.voice_input_engine_generic_error)
                        ),
                        tone = VoiceInlineBarComponent.Tone.Error
                    )
                }
            } finally {
                engineLoading = false
            }
        }
    }

    private fun renderListeningState() {
        inlineBar.show(
            VoiceInlineStatusFormatter.build(
                when (listeningState) {
                    VoiceListeningState.NOT_TALKED_YET -> context.getString(R.string.voice_input_listening_waiting)
                    VoiceListeningState.MIC_MAY_BE_BLOCKED -> context.getString(R.string.voice_input_listening_mic_blocked)
                    VoiceListeningState.TALKING -> context.getString(R.string.voice_input_release_to_finish)
                    VoiceListeningState.ENDING_SOON_VAD -> context.getString(R.string.voice_input_listening_ending_soon)
                },
                latestTranscript
            ),
            VoiceInlineBarComponent.Tone.Active
        )
    }

    private fun showTransientMessage(
        message: String,
        tone: VoiceInlineBarComponent.Tone = VoiceInlineBarComponent.Tone.Neutral,
        durationMs: Long = ERROR_MESSAGE_DURATION_MS
    ) {
        cancelHideBar()
        inlineBar.show(message, tone)
        hideBarJob =
            controllerScope.launch {
                delay(durationMs)
                inlineBar.hide()
                hideBarJob = null
            }
    }

    private fun cancelHideBar() {
        hideBarJob?.cancel()
        hideBarJob = null
    }

    private fun FloatArray.sliceRemaining(startSample: Int): FloatArray {
        val boundedStart = startSample.coerceIn(0, size)
        return if (boundedStart == 0) {
            this
        } else if (boundedStart >= size) {
            FloatArray(0)
        } else {
            copyOfRange(boundedStart, size)
        }
    }

    private fun normalizeTranscript(value: String): String =
        value.trim().replace("\\s+".toRegex(), " ")

    private fun isPasswordField(): Boolean =
        CapabilityFlags.fromEditorInfo(service.currentInputEditorInfo).has(CapabilityFlag.Password)

    private fun createControllerScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private companion object {
        private const val ENDPOINT_POLL_INTERVAL_MS = 150L
        private const val REALTIME_ANALYSIS_MAX_SAMPLES = WhisperAudioRecorder.SAMPLE_RATE * 8
        private const val ERROR_MESSAGE_DURATION_MS = 1_800L
        private const val FINAL_CONFIRM_DURATION_MS = 900L
        private const val LOG_TAG = "VoiceInlineSession"
    }
}

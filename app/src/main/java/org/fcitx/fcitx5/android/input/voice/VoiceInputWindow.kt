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
    private var partialLoopJob: Job? = null
    private var partialTranscriptionJob: Job? = null
    private var lastPartialScheduledSamples = 0

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
                ui.renderLoading()
                return
            }
            !engineReady -> {
                ensureEngineReady(force = engineErrorMessage != null && !engineModelMissing)
                renderIdle()
                return
            }
        }

        val whisperRecorder =
            runCatching { WhisperAudioRecorder().also { it.start() } }
                .getOrElse { error ->
                    recorder = null
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
        partialLoopJob?.cancel()
        partialTranscriptionJob?.cancel()
        recorder = whisperRecorder
        latestTranscript = ""
        listening = true
        processing = false
        lastPartialScheduledSamples = 0
        ui.renderListening(latestTranscript)
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
                        ui.renderMessage(context.getString(R.string.voice_input_no_speech), latestTranscript)
                        return@launch
                    }

                    val engine = voiceEngine ?: return@launch
                    val result = engine.transcribeFinal(audioData, request.locale)
                    commitTranscript(result.text)
                } catch (_: CancellationException) {
                } catch (error: Exception) {
                    processing = false
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
        partialLoopJob?.cancel()
        partialLoopJob = null
        partialTranscriptionJob?.cancel()
        partialTranscriptionJob = null
        recorder?.cancel()
        recorder = null
        listening = false
        processing = false
        ui.renderReady(latestTranscript)
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
                        if (snapshot.isNotEmpty()) {
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
                    latestTranscript = processed?.text?.ifBlank { rawText } ?: rawText
                    if (listening) {
                        ui.renderListening(latestTranscript)
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
            ui.renderMessage(context.getString(R.string.voice_input_no_speech), latestTranscript)
            return
        }

        service.commitText(committedText)
        ui.renderReady(latestTranscript)
    }

    private fun renderIdle() {
        when {
            !VoiceInputPermission.hasRecordAudioPermission(context) ->
                ui.renderPermissionRequired(
                    View.OnClickListener {
                        VoiceInputPermission.launchPermissionActivity(context)
                    }
                )
            listening -> ui.renderListening(latestTranscript)
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
        renderIdle()

        windowScope.launch {
            try {
                engine.warmup(force)
                val state = WhisperModelManager.currentState()
                engineReady = state is WhisperModelManager.State.Ready
                engineModelMissing = false
                engineErrorMessage = null
            } catch (error: Exception) {
                val state = WhisperModelManager.currentState()
                engineReady = false
                engineModelMissing = (state as? WhisperModelManager.State.Error)?.modelMissing == true
                engineErrorMessage =
                    (state as? WhisperModelManager.State.Error)?.message
                        ?: error.message
                        ?: context.getString(R.string.voice_input_engine_generic_error)
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
        private const val LOG_TAG = "VoiceInputWindow"
    }
}

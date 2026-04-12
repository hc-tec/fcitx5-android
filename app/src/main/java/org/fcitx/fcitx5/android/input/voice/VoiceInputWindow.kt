package org.fcitx.fcitx5.android.input.voice

import android.view.MotionEvent
import android.view.View
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    private var whisperState: WhisperModelManager.State.Ready? = null
    private var windowScope = createWindowScope()
    private var transcribeJob: Job? = null

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
        ensureSession()
        ensureEngineReady()
        renderIdle()
    }

    override fun onDetached() {
        transcribeJob?.cancel()
        transcribeJob = null
        recorder?.cancel()
        recorder = null
        listening = false
        processing = false
        whisperState = null
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

        when (val state = WhisperModelManager.currentState()) {
            is WhisperModelManager.State.Ready -> {
                whisperState = state
            }
            WhisperModelManager.State.Loading,
            WhisperModelManager.State.Uninitialized -> {
                ensureEngineReady()
                ui.renderLoading()
                return
            }
            is WhisperModelManager.State.Error -> {
                if (state.modelMissing) {
                    renderIdle()
                } else {
                    ensureEngineReady(force = true)
                    ui.renderLoading()
                }
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
        recorder = whisperRecorder
        latestTranscript = ""
        listening = true
        processing = false
        ui.renderListening(latestTranscript)
    }

    private fun stopListening() {
        if (!listening) {
            return
        }

        val activeRecorder = recorder ?: return
        recorder = null
        listening = false
        processing = true

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

                    val state =
                        (WhisperModelManager.currentState() as? WhisperModelManager.State.Ready)
                            ?: whisperState
                    if (state == null) {
                        processing = false
                        ensureEngineReady(force = true)
                        ui.renderLoading()
                        return@launch
                    }

                    whisperState = state
                    val rawText =
                        state.whisperContext.transcribeData(
                            audioData,
                            WhisperLanguageResolver.resolve(request.locale)
                        )
                    commitTranscript(rawText)
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
        recorder?.cancel()
        recorder = null
        listening = false
        processing = false
        ui.renderReady(latestTranscript)
    }

    private fun commitTranscript(rawText: String) {
        listening = false
        processing = false

        val transcript = rawText.trim().replace("\\s+".toRegex(), " ")
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
            else ->
                when (val state = WhisperModelManager.currentState()) {
                    is WhisperModelManager.State.Ready -> {
                        whisperState = state
                        ui.renderReady(latestTranscript)
                    }
                    WhisperModelManager.State.Loading,
                    WhisperModelManager.State.Uninitialized -> ui.renderLoading()
                    is WhisperModelManager.State.Error ->
                        ui.renderUnavailable(
                            status =
                                if (state.modelMissing) {
                                    context.getString(R.string.voice_input_model_missing)
                                } else {
                                    context.getString(R.string.voice_input_engine_error, state.message)
                                },
                            hint =
                                if (state.modelMissing) {
                                    context.getString(R.string.voice_input_model_missing_hint)
                                } else {
                                    state.message
                                }
                        )
                }
        }
    }

    private fun ensureSession() {
        if (sessionId.isBlank()) {
            sessionId = VoiceInputRuntime.sessionManager.createSession(VoiceInputContextReader.capture(service))
        }
    }

    private fun ensureEngineReady(force: Boolean = false) {
        WhisperModelManager.ensureReady(context, force) { state ->
            ui.root.post {
                if (state is WhisperModelManager.State.Ready) {
                    whisperState = state
                }
                renderIdle()
            }
        }
    }

    private fun createWindowScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
}

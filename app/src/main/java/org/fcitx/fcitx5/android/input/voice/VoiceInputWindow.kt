package org.fcitx.fcitx5.android.input.voice

import android.speech.SpeechRecognizer
import android.view.MotionEvent
import android.view.View
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
    private var recognizerClient: AndroidSpeechRecognizerClient? = null
    private var latestTranscript: String = ""
    private var listening = false
    private var processing = false

    override val title: String by lazy {
        context.getString(R.string.voice_input_title)
    }

    override fun onCreateView(): View {
        ui.setHoldTouchListener(holdTouchListener)
        return ui.root
    }

    override fun onAttached() {
        ensureSession()
        renderIdle()
    }

    override fun onDetached() {
        recognizerClient?.destroy()
        recognizerClient = null
        listening = false
        processing = false
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

    private val recognitionCallback =
        object : AndroidSpeechRecognizerClient.Callback {
            override fun onReadyForSpeech() {
                if (listening) {
                    ui.renderListening(latestTranscript)
                }
            }

            override fun onPartialResult(
                text: String,
                confidence: Double
            ) {
                val processed =
                    VoiceInputRuntime.sessionManager.processPartial(
                        sessionId,
                        VoiceRecognitionResult(text = text, confidence = confidence)
                    )
                latestTranscript = processed?.text?.ifBlank { text.trim() } ?: text.trim()
                ui.renderListening(latestTranscript)
            }

            override fun onFinalResult(
                text: String,
                confidence: Double
            ) {
                listening = false
                processing = false

                val processed =
                    VoiceInputRuntime.sessionManager.processFinal(
                        sessionId,
                        VoiceRecognitionResult(text = text, confidence = confidence)
                    )
                val committedText =
                    processed?.text?.takeIf { it.isNotBlank() }
                        ?: text.trim()
                latestTranscript = committedText

                if (committedText.isNotBlank()) {
                    service.commitText(committedText)
                }
                ui.renderReady(latestTranscript)
            }

            override fun onError(error: Int) {
                listening = false
                processing = false
                recognizerClient = null

                when (error) {
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                        ui.renderPermissionRequired(
                            View.OnClickListener {
                                VoiceInputPermission.launchPermissionActivity(context)
                            }
                        )
                    }
                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                        ui.renderMessage(
                            context.getString(R.string.voice_input_no_speech),
                            latestTranscript
                        )
                    }
                    else -> {
                        ui.renderMessage(formatRecognizerError(error), latestTranscript)
                    }
                }
            }
        }

    private fun startListening() {
        if (listening || processing) {
            return
        }

        ensureSession()
        VoiceInputRuntime.sessionManager.updateSession(sessionId, VoiceInputContextReader.capture(service))

        if (!VoiceInputPermission.hasRecordAudioPermission(context)) {
            ui.renderPermissionRequired(
                View.OnClickListener {
                    VoiceInputPermission.launchPermissionActivity(context)
                }
            )
            return
        }

        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            ui.renderUnavailable()
            return
        }

        service.finishComposing()
        latestTranscript = ""
        listening = true
        processing = false
        ui.renderListening(latestTranscript)
        recognizer().startListening(VoiceInputContextReader.resolveLocaleTag(service))
    }

    private fun stopListening() {
        if (!listening) {
            return
        }
        listening = false
        processing = true
        ui.renderProcessing(latestTranscript)
        recognizerClient?.stopListening()
    }

    private fun cancelListening() {
        if (!listening && !processing) {
            return
        }
        listening = false
        processing = false
        recognizerClient?.cancel()
        recognizerClient = null
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
            !SpeechRecognizer.isRecognitionAvailable(context) -> ui.renderUnavailable()
            else -> ui.renderReady(latestTranscript)
        }
    }

    private fun ensureSession() {
        if (sessionId.isBlank()) {
            sessionId = VoiceInputRuntime.sessionManager.createSession(VoiceInputContextReader.capture(service))
        }
    }

    private fun recognizer(): AndroidSpeechRecognizerClient {
        val existing = recognizerClient
        if (existing != null) {
            return existing
        }
        return AndroidSpeechRecognizerClient(context, recognitionCallback).also {
            recognizerClient = it
        }
    }

    private fun formatRecognizerError(error: Int): String =
        context.getString(
            R.string.voice_input_error,
            when (error) {
                SpeechRecognizer.ERROR_AUDIO -> "audio"
                SpeechRecognizer.ERROR_CLIENT -> "client"
                SpeechRecognizer.ERROR_NETWORK -> "network"
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "network timeout"
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "recognizer busy"
                SpeechRecognizer.ERROR_SERVER -> "server"
                else -> "code=$error"
            }
        )
}

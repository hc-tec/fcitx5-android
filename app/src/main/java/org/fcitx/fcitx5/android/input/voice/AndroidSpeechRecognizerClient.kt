package org.fcitx.fcitx5.android.input.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

internal class AndroidSpeechRecognizerClient(
    private val context: Context,
    private val callback: Callback
) : RecognitionListener {
    interface Callback {
        fun onReadyForSpeech()

        fun onPartialResult(
            text: String,
            confidence: Double
        )

        fun onFinalResult(
            text: String,
            confidence: Double
        )

        fun onError(error: Int)
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private var listening = false

    fun startListening(
        localeTag: String,
        preferOffline: Boolean = true
    ) {
        destroyRecognizer()
        speechRecognizer =
            SpeechRecognizer.createSpeechRecognizer(context).also {
                it.setRecognitionListener(this)
                listening = true
                it.startListening(buildIntent(localeTag, preferOffline))
            }
    }

    fun stopListening() {
        if (listening) {
            speechRecognizer?.stopListening()
        }
    }

    fun cancel() {
        speechRecognizer?.cancel()
        destroyRecognizer()
    }

    fun destroy() {
        destroyRecognizer()
    }

    override fun onReadyForSpeech(params: Bundle?) {
        callback.onReadyForSpeech()
    }

    override fun onBeginningOfSpeech() = Unit

    override fun onRmsChanged(rmsdB: Float) = Unit

    override fun onBufferReceived(buffer: ByteArray?) = Unit

    override fun onEndOfSpeech() = Unit

    override fun onError(error: Int) {
        callback.onError(error)
        destroyRecognizer()
    }

    override fun onResults(results: Bundle?) {
        callback.onFinalResult(
            text = extractPrimaryText(results),
            confidence = extractPrimaryConfidence(results)
        )
        destroyRecognizer()
    }

    override fun onPartialResults(partialResults: Bundle?) {
        val text = extractPrimaryText(partialResults)
        if (text.isBlank()) {
            return
        }
        callback.onPartialResult(
            text = text,
            confidence = extractPrimaryConfidence(partialResults)
        )
    }

    override fun onEvent(
        eventType: Int,
        params: Bundle?
    ) = Unit

    private fun buildIntent(
        localeTag: String,
        preferOffline: Boolean
    ): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, localeTag)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, localeTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOffline)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }

    private fun extractPrimaryText(results: Bundle?): String =
        results
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?.trim()
            .orEmpty()

    private fun extractPrimaryConfidence(results: Bundle?): Double {
        val scores = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES) ?: return 0.0
        val first = scores.firstOrNull() ?: return 0.0
        return if (first < 0f) 0.0 else first.toDouble()
    }

    private fun destroyRecognizer() {
        speechRecognizer?.destroy()
        speechRecognizer = null
        listening = false
    }
}

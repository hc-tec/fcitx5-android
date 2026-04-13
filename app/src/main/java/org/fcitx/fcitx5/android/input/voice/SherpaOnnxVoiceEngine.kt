package org.fcitx.fcitx5.android.input.voice

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineStream
import org.fcitx.fcitx5.android.voice.core.VoiceSessionRequest

internal class SherpaOnnxVoiceEngine(
    private val context: Context
) : VoiceEngine {
    override val engineId: String = "sherpa-onnx"

    override val capabilities =
        VoiceEngineCapabilities(
            supportsStreamingPartial = true,
            partialInitialDelayMs = 120,
            partialPollIntervalMs = 120,
            partialMinSamples = WhisperAudioRecorder.SAMPLE_RATE / 5,
            partialStepSamples = WhisperAudioRecorder.SAMPLE_RATE / 10,
            partialMaxSamples = WhisperAudioRecorder.SAMPLE_RATE * 8,
            partialAudioMode = VoicePartialAudioMode.IncrementalSession
        )

    private val sessionLock = Any()

    @Volatile
    private var pendingRequest: VoiceSessionRequest? = null

    private var activeSession: SessionState? = null

    override suspend fun warmup(force: Boolean): VoiceEngineWarmupResult {
        val state = SherpaOnnxModelManager.awaitReady(context, force)
        Log.i(LOG_TAG, "warmup force=$force state=${state::class.simpleName}")
        return when (state) {
            is SherpaOnnxModelManager.State.Ready ->
                VoiceEngineWarmupResult(
                    ready = true,
                    modelId = state.model.modelId
                )
            is SherpaOnnxModelManager.State.Error ->
                VoiceEngineWarmupResult(
                    ready = false,
                    modelMissing = state.modelMissing,
                    message = state.message
                )
            else ->
                VoiceEngineWarmupResult(
                    ready = false,
                    message = "sherpa-onnx engine is unavailable"
                )
        }
    }

    override fun beginSession(request: VoiceSessionRequest) {
        pendingRequest = request
        synchronized(sessionLock) {
            releaseSessionLocked()
        }
    }

    override fun endSession(cancelled: Boolean) {
        pendingRequest = null
        synchronized(sessionLock) {
            releaseSessionLocked()
        }
        Log.i(LOG_TAG, "Session ended cancelled=$cancelled")
    }

    override suspend fun transcribePartial(
        audioData: FloatArray,
        localeTag: String
    ): VoiceEngineResult = transcribe(audioData, localeTag, final = false)

    override suspend fun transcribeFinal(
        audioData: FloatArray,
        localeTag: String
    ): VoiceEngineResult = transcribe(audioData, localeTag, final = true)

    private suspend fun transcribe(
        audioData: FloatArray,
        localeTag: String,
        final: Boolean
    ): VoiceEngineResult {
        val state = SherpaOnnxModelManager.awaitReady(context)
        require(state is SherpaOnnxModelManager.State.Ready) {
            (state as? SherpaOnnxModelManager.State.Error)?.message ?: "sherpa-onnx engine is unavailable"
        }
        val readyState = state as SherpaOnnxModelManager.State.Ready

        val start = SystemClock.elapsedRealtime()
        val text =
            synchronized(sessionLock) {
                val request = pendingRequest ?: VoiceSessionRequest(locale = localeTag)
                val session = ensureSessionLocked(readyState, request)
                consumeAudioLocked(
                    recognizer = readyState.recognizer,
                    session = session,
                    audioData = audioData,
                    final = final
                )
            }
        val latencyMs = SystemClock.elapsedRealtime() - start
        Log.i(
            LOG_TAG,
            "engine=$engineId partial=${!final} model=${readyState.model.modelId} samples=${audioData.size} latencyMs=$latencyMs"
        )
        return VoiceEngineResult(
            text = text,
            latencyMs = latencyMs,
            sampleCount = audioData.size,
            modelId = readyState.model.modelId
        )
    }

    private fun ensureSessionLocked(
        readyState: SherpaOnnxModelManager.State.Ready,
        request: VoiceSessionRequest
    ): SessionState {
        val streamHotwords = buildStreamHotwords(readyState.model, request)
        val current = activeSession
        if (current != null && current.localeTag == request.locale && current.hotwordsSignature == streamHotwords) {
            return current
        }

        releaseSessionLocked()
        return SessionState(
            localeTag = request.locale,
            hotwordsSignature = streamHotwords,
            stream =
                if (streamHotwords.isBlank()) {
                    readyState.recognizer.createStream()
                } else {
                    readyState.recognizer.createStream(streamHotwords)
                }
        ).also { activeSession = it }
    }

    private fun consumeAudioLocked(
        recognizer: OnlineRecognizer,
        session: SessionState,
        audioData: FloatArray,
        final: Boolean
    ): String {
        if (audioData.isNotEmpty()) {
            session.stream.acceptWaveform(audioData, WhisperAudioRecorder.SAMPLE_RATE)
        }

        while (recognizer.isReady(session.stream)) {
            recognizer.decode(session.stream)
        }

        if (final && !session.inputFinished) {
            session.stream.inputFinished()
            session.inputFinished = true
            while (recognizer.isReady(session.stream)) {
                recognizer.decode(session.stream)
            }
        }

        return recognizer.getResult(session.stream).text
    }

    private fun releaseSessionLocked() {
        activeSession?.stream?.release()
        activeSession = null
    }

    private fun buildStreamHotwords(
        model: SherpaModelDescriptor,
        request: VoiceSessionRequest
    ): String {
        if (!model.supportsHotwords || request.hotwords.isEmpty()) {
            return ""
        }
        return VoiceContextHotwords.formatForSherpaStream(request.hotwords)
    }

    private data class SessionState(
        val localeTag: String,
        val stream: OnlineStream,
        val hotwordsSignature: String,
        var inputFinished: Boolean = false
    )

    private companion object {
        private const val LOG_TAG = "SherpaOnnxEngine"
    }
}

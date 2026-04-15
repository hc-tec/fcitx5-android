package org.fcitx.fcitx5.android.input.voice

import android.os.Build
import com.konovalov.vad.Vad
import com.konovalov.vad.config.FrameSize
import com.konovalov.vad.config.Mode
import com.konovalov.vad.config.Model
import com.konovalov.vad.config.SampleRate

internal object VoiceWebRtcVad {
    fun analyze(
        pcm16: ShortArray,
        sampleRate: Int = WhisperAudioRecorder.SAMPLE_RATE
    ): VoiceActivityStats? {
        if (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.N ||
            sampleRate != WhisperAudioRecorder.SAMPLE_RATE ||
            pcm16.size < FRAME_SIZE
        ) {
            return null
        }

        val vad =
            Vad.builder()
                .setModel(Model.WEB_RTC_GMM)
                .setMode(Mode.VERY_AGGRESSIVE)
                .setFrameSize(FrameSize.FRAME_SIZE_480)
                .setSampleRate(SampleRate.SAMPLE_RATE_16K)
                .setSpeechDurationMs(150)
                .setSilenceDurationMs(300)
                .build()

        val frame = ShortArray(FRAME_SIZE)
        val frameFlags = ArrayList<Boolean>(pcm16.size / FRAME_SIZE)
        var offset = 0
        while (offset + FRAME_SIZE <= pcm16.size) {
            System.arraycopy(pcm16, offset, frame, 0, FRAME_SIZE)
            frameFlags += vad.isSpeech(frame)
            offset += FRAME_SIZE
        }

        if (frameFlags.isEmpty()) {
            return null
        }

        return VoiceActivityDetector.summarize(
            samples = pcm16,
            frameFlags = frameFlags,
            frameDurationMs = FRAME_DURATION_MS
        )
    }

    private const val FRAME_SIZE = 480
    private const val FRAME_DURATION_MS = 30
}

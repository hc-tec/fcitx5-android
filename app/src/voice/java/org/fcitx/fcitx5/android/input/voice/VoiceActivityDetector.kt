package org.fcitx.fcitx5.android.input.voice

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

internal data class VoiceActivityStats(
    val hasSpeech: Boolean,
    val speechRatio: Double,
    val trailingSilenceMs: Long,
    val leadingSilenceMs: Long,
    val totalFrames: Int,
    val rms: Float,
    val peak: Float,
    val magnitude: Float
)

internal object VoiceActivityDetector {
    fun analyze(
        samples: FloatArray,
        sampleRate: Int = WhisperAudioRecorder.SAMPLE_RATE,
        frameDurationMs: Int = 30,
        rmsThreshold: Float = 0.010f,
        peakThreshold: Float = 0.045f
    ): VoiceActivityStats {
        if (samples.isEmpty()) {
            return VoiceActivityStats(
                hasSpeech = false,
                speechRatio = 0.0,
                trailingSilenceMs = 0,
                leadingSilenceMs = 0,
                totalFrames = 0,
                rms = 0.0f,
                peak = 0.0f,
                magnitude = 0.0f
            )
        }

        val frameSize = (sampleRate * frameDurationMs / 1000).coerceAtLeast(1)
        val frameFlags = mutableListOf<Boolean>()
        var offset = 0
        while (offset < samples.size) {
            val end = minOf(offset + frameSize, samples.size)
            frameFlags += isSpeechFrame(samples, offset, end, rmsThreshold, peakThreshold)
            offset = end
        }

        return summarize(samples, frameFlags, frameDurationMs)
    }

    internal fun summarize(
        samples: FloatArray,
        frameFlags: List<Boolean>,
        frameDurationMs: Int
    ): VoiceActivityStats {
        if (samples.isEmpty() || frameFlags.isEmpty()) {
            return VoiceActivityStats(
                hasSpeech = false,
                speechRatio = 0.0,
                trailingSilenceMs = 0,
                leadingSilenceMs = 0,
                totalFrames = 0,
                rms = 0.0f,
                peak = 0.0f,
                magnitude = 0.0f
            )
        }

        val sampleMetrics = computeMetrics(samples)
        return summarizeCommon(
            frameFlags = frameFlags,
            frameDurationMs = frameDurationMs,
            rms = sampleMetrics.rms,
            peak = sampleMetrics.peak
        )
    }

    internal fun summarize(
        samples: ShortArray,
        frameFlags: List<Boolean>,
        frameDurationMs: Int
    ): VoiceActivityStats {
        if (samples.isEmpty() || frameFlags.isEmpty()) {
            return VoiceActivityStats(
                hasSpeech = false,
                speechRatio = 0.0,
                trailingSilenceMs = 0,
                leadingSilenceMs = 0,
                totalFrames = 0,
                rms = 0.0f,
                peak = 0.0f,
                magnitude = 0.0f
            )
        }

        val sampleMetrics = computeMetrics(samples)
        return summarizeCommon(
            frameFlags = frameFlags,
            frameDurationMs = frameDurationMs,
            rms = sampleMetrics.rms,
            peak = sampleMetrics.peak
        )
    }

    private fun summarizeCommon(
        frameFlags: List<Boolean>,
        frameDurationMs: Int,
        rms: Float,
        peak: Float
    ): VoiceActivityStats {
        val totalFrames = frameFlags.size
        val voicedFrames = frameFlags.count { it }
        val leadingSilenceFrames = frameFlags.indexOfFirst { it }.let { if (it < 0) totalFrames else it }
        val trailingSilenceFrames = frameFlags.reversed().indexOfFirst { it }.let { if (it < 0) totalFrames else it }

        return VoiceActivityStats(
            hasSpeech = voicedFrames > 0,
            speechRatio = voicedFrames.toDouble() / totalFrames.toDouble(),
            trailingSilenceMs = trailingSilenceFrames.toLong() * frameDurationMs,
            leadingSilenceMs = leadingSilenceFrames.toLong() * frameDurationMs,
            totalFrames = totalFrames,
            rms = rms,
            peak = peak,
            magnitude = 1.0f - 0.1f.pow(24.0f * rms)
        )
    }

    private data class SampleMetrics(
        val rms: Float,
        val peak: Float
    )

    private fun computeMetrics(samples: FloatArray): SampleMetrics {
        var energy = 0.0
        var peak = 0.0f
        samples.forEach { sample ->
            energy += sample * sample
            peak = maxOf(peak, abs(sample))
        }
        val rms = sqrt(energy / samples.size.toDouble()).toFloat()
        return SampleMetrics(rms = rms, peak = peak)
    }

    private fun computeMetrics(samples: ShortArray): SampleMetrics {
        var energy = 0.0
        var peak = 0.0f
        samples.forEach { value ->
            val sample = value / PCM16_MAX
            energy += sample * sample
            peak = maxOf(peak, abs(sample))
        }
        val rms = sqrt(energy / samples.size.toDouble()).toFloat()
        return SampleMetrics(rms = rms, peak = peak)
    }

    private fun isSpeechFrame(
        samples: FloatArray,
        start: Int,
        end: Int,
        rmsThreshold: Float,
        peakThreshold: Float
    ): Boolean {
        if (start >= end) {
            return false
        }

        var energy = 0.0
        var peak = 0.0f
        for (index in start until end) {
            val sample = samples[index]
            energy += sample * sample
            peak = maxOf(peak, abs(sample))
        }

        val rms = sqrt(energy / (end - start).toDouble()).toFloat()
        return rms >= rmsThreshold || peak >= peakThreshold
    }

    private const val PCM16_MAX = 32768f
}

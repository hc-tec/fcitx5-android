package org.fcitx.fcitx5.android.input.voice

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlin.math.max

internal class WhisperAudioRecorder(
    private val sampleRate: Int = SAMPLE_RATE
) {
    private val lock = Any()
    private val chunks = mutableListOf<ShortArray>()

    @Volatile
    private var audioRecord: AudioRecord? = null

    @Volatile
    private var recordingThread: Thread? = null

    @Volatile
    private var running = false

    @Volatile
    private var totalSamples = 0

    @Volatile
    private var readErrorCode = 0

    fun start() {
        check(!running) { "Recorder is already running" }

        val minBufferSize =
            AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
        require(minBufferSize > 0) { "Unsupported audio configuration: $minBufferSize" }

        val record =
            createAudioRecord(max(minBufferSize, sampleRate / 2)).also {
                check(it.state == AudioRecord.STATE_INITIALIZED) {
                    "AudioRecord initialization failed"
                }
            }

        synchronized(lock) {
            chunks.clear()
            totalSamples = 0
            readErrorCode = 0
        }

        audioRecord = record
        running = true
        record.startRecording()
        check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
            releaseRecord(record)
            "AudioRecord failed to start recording"
        }
        Log.i(LOG_TAG, "Recorder started sampleRate=$sampleRate buffer=${max(minBufferSize, sampleRate / 2)}")

        val reader =
            Thread(
                {
                    val buffer = ShortArray(READ_FRAME_SAMPLES)
                    while (running) {
                        val count = record.read(buffer, 0, buffer.size)
                        when {
                            count > 0 -> {
                                val copy = buffer.copyOf(count)
                                synchronized(lock) {
                                    chunks += copy
                                    totalSamples += count
                                }
                            }
                            count < 0 -> {
                                readErrorCode = count
                                Log.w(LOG_TAG, "Recorder read failed code=$count")
                                running = false
                            }
                        }
                    }
                },
                "WhisperAudioRecorder"
            )
        recordingThread = reader
        reader.start()
    }

    fun stop(): FloatArray {
        val record = audioRecord ?: return FloatArray(0)
        stopInternal(record)
        val samples = drainSamples()
        Log.i(LOG_TAG, "Recorder stopped samples=${samples.size} readErrorCode=$readErrorCode")
        if (samples.isEmpty() && readErrorCode != 0) {
            throw IllegalStateException("AudioRecord read failed: $readErrorCode")
        }
        return samples
    }

    fun cancel() {
        audioRecord?.let(::stopInternal)
        synchronized(lock) {
            chunks.clear()
            totalSamples = 0
            readErrorCode = 0
        }
    }

    fun sampleCount(): Int =
        synchronized(lock) {
            totalSamples
        }

    fun snapshot(maxSamples: Int = Int.MAX_VALUE): FloatArray =
        synchronized(lock) {
            copySamplesLocked(maxSamples)
        }

    fun snapshotPcm16(maxSamples: Int = Int.MAX_VALUE): ShortArray =
        synchronized(lock) {
            copyPcm16Locked(maxSamples)
        }

    private fun stopInternal(record: AudioRecord) {
        running = false
        try {
            if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                record.stop()
            }
        } catch (_: IllegalStateException) {
        }
        recordingThread?.join(750)
        recordingThread = null
        audioRecord = null
        releaseRecord(record)
    }

    private fun drainSamples(): FloatArray {
        synchronized(lock) {
            if (totalSamples == 0) {
                chunks.clear()
                return FloatArray(0)
            }

            val result = copySamplesLocked(Int.MAX_VALUE)
            chunks.clear()
            totalSamples = 0
            readErrorCode = 0
            return result
        }
    }

    private fun copySamplesLocked(maxSamples: Int): FloatArray {
        if (totalSamples == 0) {
            return FloatArray(0)
        }

        val safeMaxSamples = maxSamples.coerceAtLeast(1)
        val resultSize = minOf(totalSamples, safeMaxSamples)
        val skipSamples = totalSamples - resultSize
        val result = FloatArray(resultSize)
        val snapshot = snapshotRanges(resultSize, skipSamples)
        snapshot.forEach { (chunk, chunkSkip) ->
            for (index in chunkSkip until chunk.size) {
                result[snapshot.offset] = chunk[index] / PCM16_MAX
                snapshot.offset += 1
            }
        }
        return result
    }

    private fun copyPcm16Locked(maxSamples: Int): ShortArray {
        if (totalSamples == 0) {
            return ShortArray(0)
        }

        val safeMaxSamples = maxSamples.coerceAtLeast(1)
        val resultSize = minOf(totalSamples, safeMaxSamples)
        val skipSamples = totalSamples - resultSize
        val result = ShortArray(resultSize)
        val snapshot = snapshotRanges(resultSize, skipSamples)
        snapshot.forEach { (chunk, chunkSkip) ->
            val length = chunk.size - chunkSkip
            System.arraycopy(chunk, chunkSkip, result, snapshot.offset, length)
            snapshot.offset += length
        }
        return result
    }

    private fun snapshotRanges(
        resultSize: Int,
        skipSamples: Int
    ): SnapshotRanges {
        val ranges = ArrayList<Pair<ShortArray, Int>>(chunks.size)
        var consumed = 0
        chunks.forEach { chunk ->
            val chunkSkip = (skipSamples - consumed).coerceIn(0, chunk.size)
            val chunkLength = chunk.size - chunkSkip
            if (chunkLength > 0) {
                ranges += chunk to chunkSkip
            }
            consumed += chunk.size
        }
        return SnapshotRanges(resultSize = resultSize, ranges = ranges)
    }

    private class SnapshotRanges(
        val resultSize: Int,
        private val ranges: List<Pair<ShortArray, Int>>
    ) : Iterable<Pair<ShortArray, Int>> {
        var offset: Int = 0
        override fun iterator(): Iterator<Pair<ShortArray, Int>> = ranges.iterator()
    }

    @Suppress("DEPRECATION")
    private fun createAudioRecord(bufferSizeInBytes: Int): AudioRecord =
        AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSizeInBytes
        )

    private fun releaseRecord(record: AudioRecord) {
        try {
            record.release()
        } catch (_: Exception) {
        }
    }

    companion object {
        const val SAMPLE_RATE = 16_000

        private const val READ_FRAME_SAMPLES = 1_600
        private const val PCM16_MAX = 32768f
        private const val LOG_TAG = "WhisperRecorder"
    }
}

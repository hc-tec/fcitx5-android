package org.fcitx.fcitx5.android.voice.core

data class VoiceSessionRequest(
    val locale: String = "",
    val packageName: String = "",
    val leftContext: String = "",
    val selectedText: String = "",
    val composingText: String = "",
    val hotwords: List<String> = emptyList()
)

data class VoiceContextSnapshot(
    val sessionId: String,
    val locale: String,
    val packageName: String,
    val leftContext: String,
    val selectedText: String,
    val composingText: String,
    val hotwords: List<String>,
    val sessionEntities: List<String>
)

data class VoiceRecognitionResult(
    val text: String,
    val confidence: Double = 0.0,
    val alternatives: List<String> = emptyList()
)

data class VoiceLowConfidenceSpan(
    val text: String,
    val reason: String
)

data class VoiceProcessedResult(
    val text: String,
    val alternatives: List<String>,
    val lowConfidenceSpans: List<VoiceLowConfidenceSpan>,
    val learnedEntities: List<String>,
    val confidence: Double
)

data class VoiceStreamResult(
    val text: String,
    val rawText: String,
    val alternatives: List<String>,
    val lowConfidenceSpans: List<VoiceLowConfidenceSpan>,
    val learnedEntities: List<String>,
    val confidence: Double,
    val stableLength: Int,
    val isFinal: Boolean
)

data class VoiceCorrectionFeedback(
    val sessionId: String,
    val originalText: String,
    val correctedText: String,
    val packageName: String = "",
    val locale: String = ""
)

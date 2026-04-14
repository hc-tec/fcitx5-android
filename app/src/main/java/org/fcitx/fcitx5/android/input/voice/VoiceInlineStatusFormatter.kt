package org.fcitx.fcitx5.android.input.voice

internal object VoiceInlineStatusFormatter {
    fun build(
        status: String,
        transcript: String
    ): String =
        transcript.takeIf { it.isNotBlank() }?.let {
            "$status  $it"
        } ?: status
}

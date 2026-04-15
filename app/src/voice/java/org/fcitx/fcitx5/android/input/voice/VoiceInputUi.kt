package org.fcitx.fcitx5.android.input.voice

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import androidx.appcompat.widget.AppCompatTextView
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme
import splitties.dimensions.dp
import splitties.views.backgroundColor
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.verticalLayout
import splitties.views.dsl.core.wrapContent

internal class VoiceInputUi(
    override val ctx: Context,
    private val theme: Theme
) : Ui {
    private val statusView =
        AppCompatTextView(ctx).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setTextColor(theme.keyTextColor)
            textSize = 16f
        }

    private val transcriptView =
        AppCompatTextView(ctx).apply {
            gravity = Gravity.START
            minLines = 3
            setTextColor(theme.keyTextColor)
            textSize = 15f
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = cardBackground()
        }

    private val hintView =
        AppCompatTextView(ctx).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setTextColor(theme.altKeyTextColor)
            textSize = 13f
        }

    private val actionButton =
        AppCompatTextView(ctx).apply {
            gravity = Gravity.CENTER
            setTextColor(theme.accentKeyTextColor)
            textSize = 14f
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = actionBackground()
            visibility = View.GONE
        }

    private val holdButton =
        AppCompatTextView(ctx).apply {
            gravity = Gravity.CENTER
            setTextColor(theme.accentKeyTextColor)
            textSize = 16f
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = holdButtonBackground(active = false, enabled = true)
        }

    override val root =
        verticalLayout {
            backgroundColor = theme.barColor
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            add(statusView, lParams(matchParent, wrapContent))
            add(transcriptView, lParams(matchParent, wrapContent) {
                topMargin = dp(12)
            })
            add(hintView, lParams(matchParent, wrapContent) {
                topMargin = dp(12)
            })
            add(actionButton, lParams(matchParent, wrapContent) {
                topMargin = dp(12)
            })
            add(holdButton, lParams(matchParent, wrapContent) {
                topMargin = dp(16)
            })
        }

    fun setHoldTouchListener(listener: View.OnTouchListener) {
        holdButton.setOnTouchListener { view, event ->
            val handled = listener.onTouch(view, event)
            if (handled && event.actionMasked == MotionEvent.ACTION_UP) {
                view.performClick()
            }
            handled
        }
    }

    fun renderReady(transcript: String) {
        statusView.text = ctx.getString(R.string.voice_input_title)
        transcriptView.text =
            transcript.ifBlank { ctx.getString(R.string.voice_input_transcript_placeholder) }
        hintView.text = ctx.getString(R.string.voice_input_ready)
        holdButton.text = ctx.getString(R.string.voice_input_hold_to_talk)
        holdButton.isEnabled = true
        holdButton.background = holdButtonBackground(active = false, enabled = true)
        hideAction()
    }

    fun renderListening(
        transcript: String,
        state: VoiceListeningState = VoiceListeningState.TALKING
    ) {
        statusView.text =
            when (state) {
                VoiceListeningState.NOT_TALKED_YET -> ctx.getString(R.string.voice_input_listening_waiting)
                VoiceListeningState.MIC_MAY_BE_BLOCKED -> ctx.getString(R.string.voice_input_listening_mic_blocked)
                VoiceListeningState.TALKING -> ctx.getString(R.string.voice_input_release_to_finish)
                VoiceListeningState.ENDING_SOON_VAD -> ctx.getString(R.string.voice_input_listening_ending_soon)
            }
        transcriptView.text =
            transcript.ifBlank { ctx.getString(R.string.voice_input_transcript_placeholder) }
        hintView.text =
            when (state) {
                VoiceListeningState.NOT_TALKED_YET -> ctx.getString(R.string.voice_input_listening_waiting_hint)
                VoiceListeningState.MIC_MAY_BE_BLOCKED -> ctx.getString(R.string.voice_input_listening_mic_blocked_hint)
                VoiceListeningState.TALKING -> ctx.getString(R.string.voice_input_release_to_finish)
                VoiceListeningState.ENDING_SOON_VAD -> ctx.getString(R.string.voice_input_listening_ending_soon_hint)
            }
        holdButton.text = ctx.getString(R.string.voice_input_release_to_finish)
        holdButton.isEnabled = true
        holdButton.background = holdButtonBackground(active = true, enabled = true)
        hideAction()
    }

    fun renderProcessing(transcript: String) {
        statusView.text = ctx.getString(R.string.voice_input_processing)
        transcriptView.text =
            transcript.ifBlank { ctx.getString(R.string.voice_input_transcript_placeholder) }
        hintView.text = ctx.getString(R.string.voice_input_processing)
        holdButton.text = ctx.getString(R.string.voice_input_processing)
        holdButton.isEnabled = false
        holdButton.background = holdButtonBackground(active = false, enabled = false)
        hideAction()
    }

    fun renderLoading() {
        statusView.text = ctx.getString(R.string.voice_input_loading_engine)
        transcriptView.text = ctx.getString(R.string.voice_input_transcript_placeholder)
        hintView.text = ctx.getString(R.string.voice_input_loading_engine_hint)
        holdButton.text = ctx.getString(R.string.voice_input_loading_engine)
        holdButton.isEnabled = false
        holdButton.background = holdButtonBackground(active = false, enabled = false)
        hideAction()
    }

    fun renderPermissionRequired(onClickListener: View.OnClickListener) {
        statusView.text = ctx.getString(R.string.voice_input_permission_required)
        transcriptView.text = ctx.getString(R.string.voice_input_transcript_placeholder)
        hintView.text = ctx.getString(R.string.voice_input_permission_message)
        holdButton.text = ctx.getString(R.string.voice_input_hold_to_talk)
        holdButton.isEnabled = false
        holdButton.background = holdButtonBackground(active = false, enabled = false)
        showAction(R.string.voice_input_grant_permission, onClickListener)
    }

    fun renderUnavailable() {
        statusView.text = ctx.getString(R.string.voice_input_unavailable)
        transcriptView.text = ctx.getString(R.string.voice_input_transcript_placeholder)
        hintView.text = ctx.getString(R.string.voice_input_unavailable)
        holdButton.text = ctx.getString(R.string.voice_input_hold_to_talk)
        holdButton.isEnabled = false
        holdButton.background = holdButtonBackground(active = false, enabled = false)
        hideAction()
    }

    fun renderUnavailable(
        status: String,
        hint: String
    ) {
        statusView.text = status
        transcriptView.text = ctx.getString(R.string.voice_input_transcript_placeholder)
        hintView.text = hint
        holdButton.text = ctx.getString(R.string.voice_input_hold_to_talk)
        holdButton.isEnabled = false
        holdButton.background = holdButtonBackground(active = false, enabled = false)
        hideAction()
    }

    fun renderMessage(
        message: String,
        transcript: String
    ) {
        statusView.text = message
        transcriptView.text =
            transcript.ifBlank { ctx.getString(R.string.voice_input_transcript_placeholder) }
        hintView.text = ctx.getString(R.string.voice_input_ready)
        holdButton.text = ctx.getString(R.string.voice_input_hold_to_talk)
        holdButton.isEnabled = true
        holdButton.background = holdButtonBackground(active = false, enabled = true)
        hideAction()
    }

    private fun showAction(
        textRes: Int,
        onClickListener: View.OnClickListener
    ) {
        actionButton.visibility = View.VISIBLE
        actionButton.text = ctx.getString(textRes)
        actionButton.setOnClickListener(onClickListener)
    }

    private fun hideAction() {
        actionButton.visibility = View.GONE
        actionButton.setOnClickListener(null)
    }

    private fun cardBackground(): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = ctx.dp(16).toFloat()
            setColor(theme.keyBackgroundColor)
            setStroke(ctx.dp(1), theme.dividerColor)
        }

    private fun actionBackground(): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = ctx.dp(14).toFloat()
            setColor(theme.accentKeyBackgroundColor)
        }

    private fun holdButtonBackground(
        active: Boolean,
        enabled: Boolean
    ): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = ctx.dp(18).toFloat()
            val fillColor =
                when {
                    !enabled -> theme.altKeyBackgroundColor
                    active -> theme.accentKeyBackgroundColor
                    else -> theme.keyBackgroundColor
                }
            val strokeColor =
                when {
                    !enabled -> theme.dividerColor
                    active -> theme.accentKeyBackgroundColor
                    else -> theme.dividerColor
                }
            setColor(fillColor)
            setStroke(ctx.dp(1), strokeColor)
        }
}

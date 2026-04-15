package org.fcitx.fcitx5.android.input.voice

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatTextView
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.CapabilityFlags
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcastReceiver
import org.fcitx.fcitx5.android.input.dependency.UniqueViewComponent
import org.fcitx.fcitx5.android.input.dependency.context
import org.fcitx.fcitx5.android.input.dependency.theme
import splitties.dimensions.dp
import splitties.views.dsl.core.add
import splitties.views.dsl.core.frameLayout
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.wrapContent

internal class VoiceHoldBubbleComponent :
    UniqueViewComponent<VoiceHoldBubbleComponent, FrameLayout>(),
    InputBroadcastReceiver {

    private val context by manager.context()
    private val theme by manager.theme()

    private val micView by lazy {
        AppCompatImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(context.dp(20), context.dp(20))
            imageTintList = ColorStateList.valueOf(theme.accentKeyTextColor)
            setImageResource(R.drawable.ic_baseline_keyboard_voice_24)
        }
    }

    private val textView by lazy {
        AppCompatTextView(context).apply {
            textSize = 14f
            setTextColor(theme.keyTextColor)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = context.dp(240)
            layoutParams =
                LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                    marginStart = context.dp(8)
                }
        }
    }

    private val chipView by lazy {
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = chipBackground()
            setPadding(context.dp(14), context.dp(10), context.dp(14), context.dp(10))
            addView(micView)
            addView(textView)
        }
    }

    private var pulseAnimator: ObjectAnimator? = null

    override val view by lazy {
        context.frameLayout {
            visibility = View.GONE
            isClickable = false
            isFocusable = false
            add(chipView, lParams(wrapContent, wrapContent))
        }
    }

    override fun onStartInput(info: EditorInfo, capFlags: CapabilityFlags) {
        hide()
    }

    override fun onVoiceInputUiStateUpdate(state: VoiceInputUiState) {
        when (state) {
            VoiceInputUiState.Idle -> hide()
            is VoiceInputUiState.Listening ->
                show(
                    text =
                        VoiceInlineStatusFormatter.build(
                            status = listeningStatusText(state.listeningState),
                            transcript = state.transcript
                        ),
                    pulsing = true
                )
            is VoiceInputUiState.Processing ->
                show(
                    text =
                        VoiceInlineStatusFormatter.build(
                            status = context.getString(R.string.voice_input_processing_short),
                            transcript = state.transcript
                        ),
                    pulsing = false
                )
        }
    }

    private fun show(text: String, pulsing: Boolean) {
        textView.text = text
        view.visibility = View.VISIBLE
        if (pulsing) {
            startPulse()
        } else {
            stopPulse()
        }
    }

    private fun hide() {
        view.visibility = View.GONE
        stopPulse()
    }

    private fun startPulse() {
        if (pulseAnimator?.isRunning == true) return
        pulseAnimator =
            ObjectAnimator.ofPropertyValuesHolder(
                micView,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.18f, 1f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.18f, 1f),
                PropertyValuesHolder.ofFloat(View.ALPHA, 0.72f, 1f, 0.72f)
            ).apply {
                duration = 820L
                repeatCount = ValueAnimator.INFINITE
                interpolator = AccelerateDecelerateInterpolator()
                start()
            }
    }

    private fun stopPulse() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        micView.scaleX = 1f
        micView.scaleY = 1f
        micView.alpha = 1f
    }

    private fun chipBackground(): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = context.dp(18).toFloat()
            setColor(theme.keyBackgroundColor)
            setStroke(context.dp(1), theme.dividerColor)
        }

    private fun listeningStatusText(state: VoiceListeningState): String =
        when (state) {
            VoiceListeningState.NOT_TALKED_YET -> context.getString(R.string.voice_input_recording_short)
            VoiceListeningState.MIC_MAY_BE_BLOCKED -> context.getString(R.string.voice_input_listening_mic_blocked)
            VoiceListeningState.TALKING -> context.getString(R.string.voice_input_release_to_finish)
            VoiceListeningState.ENDING_SOON_VAD -> context.getString(R.string.voice_input_listening_ending_soon)
        }
}

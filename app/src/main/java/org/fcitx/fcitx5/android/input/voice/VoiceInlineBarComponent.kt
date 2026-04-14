package org.fcitx.fcitx5.android.input.voice

import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import androidx.appcompat.widget.AppCompatTextView
import org.fcitx.fcitx5.android.input.bar.KawaiiBarComponent
import org.fcitx.fcitx5.android.input.dependency.UniqueViewComponent
import org.fcitx.fcitx5.android.input.dependency.context
import org.fcitx.fcitx5.android.input.dependency.theme
import splitties.dimensions.dp
import splitties.views.dsl.core.add
import splitties.views.dsl.core.frameLayout
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent

internal class VoiceInlineBarComponent :
    UniqueViewComponent<VoiceInlineBarComponent, FrameLayout>() {

    enum class Tone {
        Neutral,
        Active,
        Error
    }

    private val context by manager.context()
    private val theme by manager.theme()

    private val messageView by lazy {
        AppCompatTextView(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            textSize = 14f
            setPadding(context.dp(14), 0, context.dp(14), 0)
        }
    }

    override val view by lazy {
        context.frameLayout {
            visibility = View.GONE
            isClickable = false
            isFocusable = false
            add(messageView, lParams(matchParent, matchParent))
        }
    }

    fun show(
        text: String,
        tone: Tone = Tone.Neutral,
        onClick: (() -> Unit)? = null
    ) {
        messageView.text = text
        messageView.background = backgroundForTone(tone)
        messageView.setTextColor(
            when (tone) {
                Tone.Active -> theme.accentKeyTextColor
                Tone.Error -> theme.keyTextColor
                Tone.Neutral -> theme.keyTextColor
            }
        )
        view.visibility = View.VISIBLE
        view.isClickable = onClick != null
        view.isFocusable = onClick != null
        view.setOnClickListener(
            onClick?.let { callback ->
                View.OnClickListener { callback() }
            }
        )
    }

    fun hide() {
        view.visibility = View.GONE
        view.isClickable = false
        view.isFocusable = false
        view.setOnClickListener(null)
    }

    private fun backgroundForTone(tone: Tone): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = context.dp(12).toFloat()
            val (fillColor, strokeColor) =
                when (tone) {
                    Tone.Active -> theme.accentKeyBackgroundColor to theme.accentKeyBackgroundColor
                    Tone.Error -> theme.altKeyBackgroundColor to theme.dividerColor
                    Tone.Neutral -> theme.keyBackgroundColor to theme.dividerColor
                }
            setColor(fillColor)
            setStroke(context.dp(1), strokeColor)
        }

    companion object {
        const val HEIGHT = KawaiiBarComponent.HEIGHT
    }
}

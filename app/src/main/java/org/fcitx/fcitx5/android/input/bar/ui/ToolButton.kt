/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import androidx.appcompat.widget.AppCompatTextView
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.keyboard.CustomGestureView
import org.fcitx.fcitx5.android.utils.borderlessRippleDrawable
import org.fcitx.fcitx5.android.utils.circlePressHighlightDrawable
import splitties.dimensions.dp
import splitties.views.dsl.core.add
import splitties.views.dsl.core.imageView
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.wrapContent
import splitties.views.gravityCenter
import splitties.views.imageResource
import splitties.views.padding

class ToolButton(context: Context) : CustomGestureView(context) {

    companion object {
        val disableAnimation by AppPrefs.getInstance().advanced.disableAnimation
    }

    val image = imageView {
        isClickable = false
        isFocusable = false
        padding = dp(10)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
    }

    private var defaultImageTintList: ColorStateList? = null

    private val monogramView =
        AppCompatTextView(context).apply {
            isClickable = false
            isFocusable = false
            gravity = Gravity.CENTER
            includeFontPadding = false
            maxLines = 1
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.04f
            visibility = GONE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        }

    private val badgeDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
        }

    private val badgeView =
        View(context).apply {
            isClickable = false
            isFocusable = false
            background = badgeDrawable
            visibility = GONE
        }

    init {
        add(image, lParams(wrapContent, wrapContent, gravityCenter))
        add(monogramView, lParams(wrapContent, wrapContent, gravityCenter))
        add(
            badgeView,
            lParams(dp(8), dp(8), Gravity.TOP or Gravity.END) {
                setMargins(0, dp(6), dp(6), 0)
            }
        )
    }

    constructor(context: Context, @DrawableRes icon: Int, theme: Theme) : this(context) {
        defaultImageTintList = ColorStateList.valueOf(theme.altKeyTextColor)
        image.imageTintList = defaultImageTintList
        setMonogramTextColor(theme.altKeyTextColor)
        setIcon(icon)
        setPressHighlightColor(theme.keyPressHighlightColor)
        setBadgeColor(theme.accentKeyBackgroundColor)
    }

    fun setIcon(@DrawableRes icon: Int) {
        image.imageTintList = defaultImageTintList
        image.imageResource = icon
        image.visibility = VISIBLE
        monogramView.visibility = GONE
    }

    fun setAssetIcon(drawable: Drawable) {
        image.imageTintList = null
        image.setImageDrawable(drawable)
        image.visibility = VISIBLE
        monogramView.visibility = GONE
    }

    fun setMonogram(text: String?) {
        if (text.isNullOrBlank()) {
            monogramView.text = ""
            monogramView.visibility = GONE
            image.visibility = VISIBLE
            return
        }

        monogramView.text = text
        monogramView.visibility = VISIBLE
        image.visibility = GONE
    }

    fun setMonogramTextColor(@ColorInt color: Int) {
        monogramView.setTextColor(color)
    }

    fun setPressHighlightColor(@ColorInt color: Int) {
        background = if (disableAnimation) {
            circlePressHighlightDrawable(color)
        } else {
            borderlessRippleDrawable(color, dp(20))
        }
    }

    fun setBadgeVisible(visible: Boolean) {
        badgeView.visibility = if (visible) VISIBLE else GONE
    }

    fun setBadgeColor(@ColorInt color: Int) {
        badgeDrawable.setColor(color)
    }
}

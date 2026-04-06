/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.functionkit

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme
import splitties.dimensions.dp

internal sealed class FunctionKitBindingCardItem {
    abstract val stableKey: String

    data class Binding(val entry: FunctionKitBindingEntry) : FunctionKitBindingCardItem() {
        override val stableKey: String = entry.stableId
    }

    data class LocalPaste(val text: String) : FunctionKitBindingCardItem() {
        override val stableKey: String = "__paste__"
    }

    data object OpenDownloadCenter : FunctionKitBindingCardItem() {
        override val stableKey: String = "__download_center__"
    }
}

internal class FunctionKitBindingsAdapter(
    private val theme: Theme,
    private val accentColor: Int,
    private val cardBackgroundColor: Int,
    private val cardBorderColor: Int,
    private val iconSurfaceColor: Int,
    private val mutedSurfaceColor: Int,
    private val accentSoftColor: Int,
    private val primaryTextColor: Int,
    private val secondaryTextColor: Int,
    private val tertiaryTextColor: Int,
    private val onClick: (FunctionKitBindingCardItem) -> Unit,
    private val onPinToggle: (FunctionKitBindingEntry) -> Unit,
    private val isPinned: (FunctionKitBindingEntry) -> Boolean
) : RecyclerView.Adapter<FunctionKitBindingsAdapter.CardHolder>() {

    data class CardViewRefs(
        val rootBackground: GradientDrawable,
        val iconSlotBackground: GradientDrawable,
        val pinBackground: GradientDrawable,
        val iconView: ImageView,
        val titleView: TextView,
        val subtitleView: TextView,
        val metaRow: LinearLayout,
        val pinView: ImageView
    )

    var items: List<FunctionKitBindingCardItem> = emptyList()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    private val iconCache = HashMap<String, android.graphics.drawable.Drawable?>()

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = items[position].stableKey.hashCode().toLong()

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CardHolder {
        val (view, refs) = createCardView(parent)
        return CardHolder(view, refs)
    }

    override fun onBindViewHolder(holder: CardHolder, position: Int) {
        holder.bind(items[position])
    }

    private fun roundedDrawable(
        context: Context,
        color: Int,
        cornerDp: Int,
        strokeColor: Int? = null
    ) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = context.dp(cornerDp).toFloat()
        setColor(color)
        if (strokeColor != null) {
            setStroke(context.dp(1), strokeColor)
        }
    }

    private fun createCardView(parent: ViewGroup): Pair<View, CardViewRefs> {
        val context = parent.context
        val cardBackground = roundedDrawable(context, cardBackgroundColor, cornerDp = 20, strokeColor = cardBorderColor)
        val maskDrawable = roundedDrawable(context, Color.WHITE, cornerDp = 20)
        val iconSlotBackground = roundedDrawable(context, iconSurfaceColor, cornerDp = 16)
        val pinBackground = roundedDrawable(context, Color.TRANSPARENT, cornerDp = 999)

        val iconSlotId = View.generateViewId()
        val pinViewId = View.generateViewId()
        val titleViewId = View.generateViewId()
        val subtitleViewId = View.generateViewId()

        val iconSlot =
            FrameLayout(context).apply {
                id = iconSlotId
                background = iconSlotBackground
            }
        val iconView =
            ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_INSIDE
            }
        iconSlot.addView(
            iconView,
            FrameLayout.LayoutParams(context.dp(24), context.dp(24), Gravity.CENTER)
        )

        val titleView =
            TextView(context).apply {
                id = titleViewId
                setTextColor(primaryTextColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTypeface(typeface, Typeface.BOLD)
                includeFontPadding = false
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }

        val subtitleView =
            TextView(context).apply {
                id = subtitleViewId
                setTextColor(secondaryTextColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                includeFontPadding = false
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }

        val metaRow =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isVisible = false
            }

        val pinView =
            ImageView(context).apply {
                id = pinViewId
                background = pinBackground
                isClickable = true
                isFocusable = true
                isVisible = false
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(context.dp(6), context.dp(6), context.dp(6), context.dp(6))
            }

        val root =
            ConstraintLayout(context).apply {
                background = cardBackground
                minimumHeight = context.dp(98)
                elevation = context.dp(1).toFloat()
                foreground =
                    RippleDrawable(
                        ColorStateList.valueOf(theme.keyPressHighlightColor),
                        null,
                        maskDrawable
                    )
                isClickable = true
                isFocusable = true
                addView(
                    iconSlot,
                    ConstraintLayout.LayoutParams(context.dp(52), context.dp(52)).apply {
                        startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                        topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                        marginStart = context.dp(14)
                        topMargin = context.dp(14)
                    }
                )
                addView(
                    pinView,
                    ConstraintLayout.LayoutParams(context.dp(30), context.dp(30)).apply {
                        endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                        topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                        marginEnd = context.dp(12)
                        topMargin = context.dp(12)
                    }
                )
                addView(
                    titleView,
                    ConstraintLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        startToEnd = iconSlotId
                        endToStart = pinViewId
                        topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                        marginStart = context.dp(12)
                        marginEnd = context.dp(8)
                        topMargin = context.dp(14)
                    }
                )
                addView(
                    subtitleView,
                    ConstraintLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        startToStart = titleViewId
                        endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                        topToBottom = titleViewId
                        marginEnd = context.dp(14)
                        topMargin = context.dp(4)
                    }
                )
                addView(
                    metaRow,
                    ConstraintLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        startToStart = titleViewId
                        endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                        topToBottom = subtitleViewId
                        bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                        marginEnd = context.dp(14)
                        topMargin = context.dp(10)
                        bottomMargin = context.dp(14)
                    }
                )
            }

        return root to
            CardViewRefs(
                rootBackground = cardBackground,
                iconSlotBackground = iconSlotBackground,
                pinBackground = pinBackground,
                iconView = iconView,
                titleView = titleView,
                subtitleView = subtitleView,
                metaRow = metaRow,
                pinView = pinView
            )
    }

    inner class CardHolder(
        private val root: View,
        private val refs: CardViewRefs
    ) : RecyclerView.ViewHolder(root) {

        fun bind(item: FunctionKitBindingCardItem) {
            when (item) {
                is FunctionKitBindingCardItem.Binding -> bindBinding(item.entry)
                is FunctionKitBindingCardItem.LocalPaste -> bindPaste()
                FunctionKitBindingCardItem.OpenDownloadCenter -> bindDownloadCenter()
            }

            root.setOnClickListener { onClick(item) }
        }

        private fun applyStyle(
            cardColor: Int,
            strokeColor: Int,
            iconContainerColor: Int
        ) {
            refs.rootBackground.setColor(cardColor)
            refs.rootBackground.setStroke(root.context.dp(1), strokeColor)
            refs.iconSlotBackground.setColor(iconContainerColor)
            refs.titleView.setTextColor(primaryTextColor)
            refs.subtitleView.setTextColor(secondaryTextColor)
        }

        private fun setMetaChips(labels: List<String>) {
            refs.metaRow.removeAllViews()
            refs.metaRow.isVisible = labels.isNotEmpty()
            labels.forEachIndexed { index, label ->
                val chip =
                    TextView(root.context).apply {
                        text = label
                        setTextColor(tertiaryTextColor)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                        setTypeface(typeface, Typeface.BOLD)
                        includeFontPadding = false
                        background = roundedDrawable(root.context, mutedSurfaceColor, cornerDp = 999)
                        setPadding(
                            root.context.dp(10),
                            root.context.dp(5),
                            root.context.dp(10),
                            root.context.dp(5)
                        )
                    }
                refs.metaRow.addView(
                    chip,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        if (index > 0) {
                            marginStart = root.context.dp(6)
                        }
                    }
                )
            }
        }

        private fun bindPaste() {
            applyStyle(
                cardColor = mutedSurfaceColor,
                strokeColor = cardBorderColor,
                iconContainerColor = Color.WHITE
            )
            refs.titleView.maxLines = 1
            refs.subtitleView.maxLines = 2
            refs.titleView.text = root.context.getString(android.R.string.paste)
            refs.subtitleView.text = root.context.getString(R.string.function_kit_bindings_paste_subtitle)
            refs.iconView.setImageResource(R.drawable.ic_baseline_content_paste_24)
            refs.iconView.setColorFilter(accentColor)
            refs.pinView.isVisible = false
            refs.pinView.setOnClickListener(null)
            refs.pinBackground.setColor(Color.TRANSPARENT)
            setMetaChips(emptyList())
        }

        private fun bindDownloadCenter() {
            applyStyle(
                cardColor = accentSoftColor,
                strokeColor = accentSoftColor,
                iconContainerColor = Color.WHITE
            )
            refs.titleView.maxLines = 2
            refs.subtitleView.maxLines = 2
            refs.titleView.text = root.context.getString(R.string.function_kit_bindings_open_download_center)
            refs.subtitleView.text = root.context.getString(R.string.function_kit_bindings_open_download_center_subtitle)
            refs.iconView.setImageResource(R.drawable.ic_baseline_storefront_24)
            refs.iconView.setColorFilter(accentColor)
            refs.pinView.isVisible = false
            refs.pinView.setOnClickListener(null)
            refs.pinBackground.setColor(Color.TRANSPARENT)
            setMetaChips(emptyList())
        }

        private fun bindBinding(entry: FunctionKitBindingEntry) {
            applyStyle(
                cardColor = cardBackgroundColor,
                strokeColor = cardBorderColor,
                iconContainerColor = mutedSurfaceColor
            )
            refs.titleView.maxLines = 2
            refs.subtitleView.maxLines = 1
            refs.titleView.text = entry.title
            refs.subtitleView.text = buildSubtitle(entry)
            setMetaChips(buildMetaLabels(entry))

            val assetPath = entry.kitIconAssetPath
            val icon =
                if (assetPath.isNullOrBlank()) {
                    null
                } else {
                    iconCache.getOrPut(assetPath) {
                        FunctionKitIconLoader.loadDrawable(root.context, assetPath)
                    }
                }
            if (icon != null) {
                refs.iconView.clearColorFilter()
                refs.iconView.setImageDrawable(icon)
            } else {
                refs.iconView.setImageResource(R.drawable.ic_baseline_auto_awesome_24)
                refs.iconView.setColorFilter(secondaryTextColor)
            }

            val pinned = isPinned(entry)
            refs.pinView.isVisible = true
            refs.pinBackground.setColor(if (pinned) accentSoftColor else mutedSurfaceColor)
            refs.pinView.setImageResource(
                if (pinned) {
                    R.drawable.ic_baseline_star_24
                } else {
                    R.drawable.ic_baseline_star_border_24
                }
            )
            refs.pinView.setColorFilter(if (pinned) accentColor else tertiaryTextColor)
            refs.pinView.setOnClickListener { onPinToggle(entry) }
        }

        private fun buildSubtitle(entry: FunctionKitBindingEntry): String {
            val kitLabel = entry.kitLabel.trim()
            if (kitLabel.isNotBlank()) {
                return kitLabel
            }
            return entry.categories
                ?.map { it.trim() }
                ?.firstOrNull { it.isNotBlank() }
                .orEmpty()
        }

        private fun buildMetaLabels(entry: FunctionKitBindingEntry): List<String> {
            val categories =
                entry.categories
                    ?.map { it.trim() }
                    ?.filter { it.isNotBlank() }
                    ?.sortedBy { it.lowercase() }
                    .orEmpty()
            if (categories.isEmpty()) {
                return emptyList()
            }
            if (categories.size <= 2) {
                return categories
            }
            return listOf(categories[0], categories[1], "+${categories.size - 2}")
        }
    }
}

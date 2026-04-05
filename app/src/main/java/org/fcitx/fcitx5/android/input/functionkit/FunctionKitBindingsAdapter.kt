/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.functionkit

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
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
    private val iconSurfaceColor: Int,
    private val primaryTextColor: Int,
    private val secondaryTextColor: Int,
    private val onClick: (FunctionKitBindingCardItem) -> Unit,
    private val onPinToggle: (FunctionKitBindingEntry) -> Unit,
    private val isPinned: (FunctionKitBindingEntry) -> Boolean
) : RecyclerView.Adapter<FunctionKitBindingsAdapter.CardHolder>() {

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

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CardHolder =
        CardHolder(createCardView(parent))

    override fun onBindViewHolder(holder: CardHolder, position: Int) {
        holder.bind(items[position])
    }

    private fun createCardView(parent: ViewGroup): View {
        val cornerRadius = parent.context.dp(16).toFloat()
        val cardBackground =
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                this.cornerRadius = cornerRadius
                setColor(cardBackgroundColor)
            }
        val maskDrawable =
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                this.cornerRadius = cornerRadius
                setColor(Color.WHITE)
            }

        val iconSlot =
            FrameLayout(parent.context).apply {
                id = android.R.id.background
                val iconCorner = parent.context.dp(14).toFloat()
                background =
                    GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        this.cornerRadius = iconCorner
                        setColor(iconSurfaceColor)
                    }
            }

        val iconView =
            ImageView(parent.context).apply {
                id = android.R.id.icon
                scaleType = ImageView.ScaleType.CENTER_INSIDE
            }
        iconSlot.addView(
            iconView,
            FrameLayout.LayoutParams(parent.context.dp(26), parent.context.dp(26), Gravity.CENTER)
        )

        val titleView =
            TextView(parent.context).apply {
                id = android.R.id.text1
                setTextColor(primaryTextColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTypeface(typeface, Typeface.BOLD)
                includeFontPadding = false
                maxLines = 2
            }
        val subtitleView =
            TextView(parent.context).apply {
                id = android.R.id.text2
                setTextColor(secondaryTextColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                includeFontPadding = false
                maxLines = 2
            }

        val textColumn =
            LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                addView(
                    titleView,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
                addView(
                    subtitleView,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        topMargin = parent.context.dp(2)
                    }
                )
            }

        val pinView =
            ImageView(parent.context).apply {
                id = R.id.function_kit_binding_pin
                isClickable = true
                isFocusable = true
                isVisible = false
            }

        val contentRow =
            LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(parent.context.dp(14), parent.context.dp(14), parent.context.dp(14), parent.context.dp(14))
                addView(
                    iconSlot,
                    LinearLayout.LayoutParams(parent.context.dp(58), parent.context.dp(58)).apply {
                        marginEnd = parent.context.dp(14)
                    }
                )
                addView(
                    textColumn,
                    LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        1f
                    )
                )
            }

        return FrameLayout(parent.context).apply {
            background = cardBackground
            foreground =
                RippleDrawable(
                    ColorStateList.valueOf(theme.keyPressHighlightColor),
                    null,
                    maskDrawable
                )
            isClickable = true
            isFocusable = true
            addView(
                contentRow,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            addView(
                pinView,
                FrameLayout.LayoutParams(parent.context.dp(28), parent.context.dp(28), Gravity.END or Gravity.TOP).apply {
                    topMargin = parent.context.dp(10)
                    marginEnd = parent.context.dp(10)
                }
            )
        }
    }

    inner class CardHolder(private val view: View) : RecyclerView.ViewHolder(view) {
        private val iconView: ImageView = view.findViewById(android.R.id.icon)
        private val titleView: TextView = view.findViewById(android.R.id.text1)
        private val subtitleView: TextView = view.findViewById(android.R.id.text2)
        private val pinView: ImageView = view.findViewById(R.id.function_kit_binding_pin)

        fun bind(item: FunctionKitBindingCardItem) {
            when (item) {
                is FunctionKitBindingCardItem.Binding -> bindBinding(item.entry)
                is FunctionKitBindingCardItem.LocalPaste -> bindPaste()
                FunctionKitBindingCardItem.OpenDownloadCenter -> bindDownloadCenter()
            }

            view.setOnClickListener {
                onClick(item)
            }
        }

        private fun bindPaste() {
            titleView.text = view.context.getString(android.R.string.paste)
            subtitleView.text = view.context.getString(R.string.function_kit_bindings_clipboard)
            iconView.setImageResource(R.drawable.ic_baseline_content_paste_24)
            iconView.setColorFilter(secondaryTextColor)
            pinView.isVisible = false
        }

        private fun bindDownloadCenter() {
            titleView.text = view.context.getString(R.string.function_kit_bindings_open_download_center)
            subtitleView.text = view.context.getString(R.string.function_kit_bindings_open_download_center_subtitle)
            iconView.setImageResource(R.drawable.ic_baseline_storefront_24)
            iconView.setColorFilter(accentColor)
            pinView.isVisible = false
        }

        private fun bindBinding(entry: FunctionKitBindingEntry) {
            titleView.text = entry.title
            subtitleView.text = buildSubtitle(entry)

            val assetPath = entry.kitIconAssetPath
            val icon =
                if (assetPath.isNullOrBlank()) {
                    null
                } else {
                    iconCache.getOrPut(assetPath) {
                        FunctionKitIconLoader.loadDrawable(view.context, assetPath)
                    }
                }
            if (icon != null) {
                iconView.clearColorFilter()
                iconView.setImageDrawable(icon)
            } else {
                iconView.setImageResource(R.drawable.ic_baseline_auto_awesome_24)
                iconView.setColorFilter(secondaryTextColor)
            }

            val pinned = isPinned(entry)
            pinView.isVisible = true
            pinView.setImageResource(
                if (pinned) {
                    R.drawable.ic_baseline_star_24
                } else {
                    R.drawable.ic_baseline_star_border_24
                }
            )
            pinView.setColorFilter(if (pinned) accentColor else secondaryTextColor)
            pinView.setOnClickListener {
                onPinToggle(entry)
            }
        }

        private fun buildSubtitle(entry: FunctionKitBindingEntry): String {
            val parts = mutableListOf(entry.kitLabel)
            val categories =
                entry.categories
                    ?.map { it.trim() }
                    ?.filter { it.isNotBlank() }
                    ?.sortedWith(compareBy { it.lowercase() })
                    .orEmpty()
            if (categories.isNotEmpty()) {
                parts += categories.joinToString(", ")
            }
            return parts.joinToString(" · ")
        }
    }
}

/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.status

import androidx.annotation.DrawableRes
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.Action
import org.fcitx.fcitx5.android.input.functionkit.FunctionKitBindingEntry

sealed class StatusAreaEntry(
    val label: String,
    @DrawableRes
    val icon: Int,
    val active: Boolean,
    val iconAssetPath: String? = null
) {
    class Android(
        label: String,
        icon: Int,
        val type: Type,
        val functionKitId: String? = null,
        iconAssetPath: String? = null
    ) :
        StatusAreaEntry(label, icon, false, iconAssetPath) {
        enum class Type {
            FunctionKit,
            FunctionKitBindings,
            FunctionKitSettings,
            FunctionKitTasks,
            InputMethod,
            ReloadConfig,
            Keyboard,
            ThemeList
        }
    }

    class Fcitx(val action: Action, label: String, icon: Int, active: Boolean) :
        StatusAreaEntry(label, icon, active, null)

    internal class FunctionKitBindingAction(
        val binding: FunctionKitBindingEntry
    ) :
        StatusAreaEntry(
            binding.title,
            R.drawable.ic_baseline_auto_awesome_24,
            false,
            binding.kitIconAssetPath
        )

    internal class LocalAction(
        val actionId: String,
        label: String,
        icon: Int
    ) : StatusAreaEntry(label, icon, false, null)

    companion object {
        private fun drawableFromIconName(icon: String) = when (icon) {
            // androidkeyboard
            "tools-check-spelling" -> R.drawable.ic_baseline_spellcheck_24
            // fcitx5-chinese-addons
            "fcitx-chttrans-active" -> R.drawable.ic_fcitx_status_chttrans_trad
            "fcitx-chttrans-inactive" -> R.drawable.ic_fcitx_status_chttrans_simp
            "fcitx-punc-active" -> R.drawable.ic_fcitx_status_punc_active
            "fcitx-punc-inactive" -> R.drawable.ic_fcitx_status_punc_inactive
            "fcitx-fullwidth-active" -> R.drawable.ic_fcitx_status_fullwidth_active
            "fcitx-fullwidth-inactive" -> R.drawable.ic_fcitx_status_fullwidth_inactive
            "fcitx-remind-active" -> R.drawable.ic_fcitx_status_prediction_active
            "fcitx-remind-inactive" -> R.drawable.ic_fcitx_status_prediction_inactive
            // fcitx5-unikey
            "document-edit" -> R.drawable.ic_baseline_edit_24
            "character-set" -> R.drawable.ic_baseline_text_format_24
            "edit-find" -> R.drawable.ic_baseline_search_24
            // fallback
            "" -> 0
            else -> {
                if (icon.endsWith("-inactive")) {
                    R.drawable.ic_baseline_code_off_24
                } else {
                    R.drawable.ic_baseline_code_24
                }
            }
        }

        fun fromAction(it: Action): Fcitx {
            val active = it.icon.endsWith("-active") || it.isChecked
            return Fcitx(it, it.shortText, drawableFromIconName(it.icon), active)
        }
    }
}

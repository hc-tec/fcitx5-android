/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.prefs

import android.content.SharedPreferences
import android.os.Build
import androidx.annotation.Keep
import androidx.annotation.RequiresApi
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.InputFeedbacks.InputFeedbackMode
import org.fcitx.fcitx5.android.input.candidates.expanded.ExpandedCandidateStyle
import org.fcitx.fcitx5.android.input.candidates.floating.FloatingCandidatesMode
import org.fcitx.fcitx5.android.input.candidates.floating.FloatingCandidatesOrientation
import org.fcitx.fcitx5.android.input.candidates.horizontal.HorizontalCandidateMode
import org.fcitx.fcitx5.android.input.keyboard.LangSwitchBehavior
import org.fcitx.fcitx5.android.input.keyboard.SpaceLongPressBehavior
import org.fcitx.fcitx5.android.input.keyboard.SwipeSymbolDirection
import org.fcitx.fcitx5.android.input.picker.PickerWindow
import org.fcitx.fcitx5.android.input.popup.EmojiModifier
import org.fcitx.fcitx5.android.input.voice.VoiceModelPreference
import org.fcitx.fcitx5.android.input.voice.VoiceInputMode
import org.fcitx.fcitx5.android.utils.DeviceUtil
import org.fcitx.fcitx5.android.utils.appContext
import org.fcitx.fcitx5.android.utils.vibrator

class AppPrefs(private val sharedPreferences: SharedPreferences) {

    inner class Internal : ManagedPreferenceInternal(sharedPreferences) {
        val firstRun = bool("first_run", true)
        val lastSymbolLayout = string("last_symbol_layout", PickerWindow.Key.Symbol.name)
        val lastPickerType = string("last_picker_type", PickerWindow.Key.Emoji.name)
        val verboseLog = bool("verbose_log", false)
        val pid = int("pid", 0)
        val editorInfoInspector = bool("editor_info_inspector", false)
        val needNotifications = bool("need_notifications", true)
        val migratedFunctionKitToolbarShortcutDefaultOn =
            bool("migrated_function_kit_toolbar_shortcut_default_on", false)
        val migratedVoiceSpaceLongPressDefaultOn =
            bool("migrated_voice_space_long_press_default_on", false)
    }

    inner class Advanced : ManagedPreferenceCategory(R.string.advanced, sharedPreferences) {
        val ignoreSystemCursor = switch(R.string.ignore_sys_cursor, "ignore_system_cursor", false)
        val hideKeyConfig = switch(R.string.hide_key_config, "hide_key_config", true)
        val disableAnimation = switch(R.string.disable_animation, "disable_animation", false)
        val vivoKeypressWorkaround = switch(
            R.string.vivo_keypress_workaround,
            "vivo_keypress_workaround",
            // there's some feedback that this workaround is no longer necessary on Origin OS 4, which based on Android 14
            Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE && DeviceUtil.isVivoOriginOS
        )
        val ignoreSystemWindowInsets = switch(
            R.string.ignore_system_window_insets, "ignore_system_window_insets", false
        )
    }

    inner class Keyboard : ManagedPreferenceCategory(R.string.virtual_keyboard, sharedPreferences) {
        val hapticOnKeyPress =
            enumList(
                R.string.button_haptic_feedback,
                "haptic_on_keypress",
                InputFeedbackMode.FollowingSystem
            )
        val hapticOnKeyUp = switch(
            R.string.button_up_haptic_feedback,
            "haptic_on_keyup",
            false
        ) { hapticOnKeyPress.getValue() != InputFeedbackMode.Disabled }
        val hapticOnRepeat = switch(R.string.haptic_on_repeat, "haptic_on_repeat", false)

        val buttonPressVibrationMilliseconds: ManagedPreference.PInt
        val buttonLongPressVibrationMilliseconds: ManagedPreference.PInt

        init {
            val (primary, secondary) = twinInt(
                R.string.button_vibration_milliseconds,
                R.string.button_press,
                "button_vibration_press_milliseconds",
                0,
                R.string.button_long_press,
                "button_vibration_long_press_milliseconds",
                0,
                0,
                100,
                "ms",
                defaultLabel = R.string.system_default
            ) { hapticOnKeyPress.getValue() != InputFeedbackMode.Disabled }
            buttonPressVibrationMilliseconds = primary
            buttonLongPressVibrationMilliseconds = secondary
        }

        val buttonPressVibrationAmplitude: ManagedPreference.PInt
        val buttonLongPressVibrationAmplitude: ManagedPreference.PInt

        init {
            val (primary, secondary) = twinInt(
                R.string.button_vibration_amplitude,
                R.string.button_press,
                "button_vibration_press_amplitude",
                0,
                R.string.button_long_press,
                "button_vibration_long_press_amplitude",
                0,
                0,
                255,
                defaultLabel = R.string.system_default
            ) {
                (hapticOnKeyPress.getValue() != InputFeedbackMode.Disabled)
                        // hide this if using default duration
                        && (buttonPressVibrationMilliseconds.getValue() != 0 || buttonLongPressVibrationMilliseconds.getValue() != 0)
                        && (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && appContext.vibrator.hasAmplitudeControl())
            }
            buttonPressVibrationAmplitude = primary
            buttonLongPressVibrationAmplitude = secondary
        }

        val soundOnKeyPress = enumList(
            R.string.button_sound,
            "sound_on_keypress",
            InputFeedbackMode.FollowingSystem
        )
        val soundOnKeyPressVolume = int(
            R.string.button_sound_volume,
            "button_sound_volume",
            0,
            0,
            100,
            "%",
            defaultLabel = R.string.system_default
        ) {
            soundOnKeyPress.getValue() != InputFeedbackMode.Disabled
        }
        val focusChangeResetKeyboard =
            switch(R.string.reset_keyboard_on_focus_change, "reset_keyboard_on_focus_change", true)
        val expandToolbarByDefault =
            switch(R.string.expand_toolbar_by_default, "expand_toolbar_by_default", true)
        val inlineSuggestions = switch(R.string.inline_suggestions, "inline_suggestions", true)
        val toolbarNumRowOnPassword =
            switch(R.string.toolbar_num_row_on_password, "toolbar_num_row_on_password", true)
        val popupOnKeyPress = switch(R.string.popup_on_key_press, "popup_on_key_press", true)
        val keepLettersUppercase = switch(
            R.string.keep_keyboard_letters_uppercase,
            "keep_keyboard_letters_uppercase",
            false
        )

        val showVoiceInputButton =
            switch(R.string.show_voice_input_button, "show_voice_input_button", false)
        val voiceInputMode = enumList(
            R.string.voice_input_mode,
            "voice_input_mode",
            VoiceInputMode.BuiltInSpeechRecognizer
        ) { showVoiceInputButton.getValue() }
        val preferredVoiceInput = voiceInputPreference(
            R.string.preferred_voice_input, "preferred_voice_input", ""
        ) {
            showVoiceInputButton.getValue() && voiceInputMode.getValue() == VoiceInputMode.SystemVoiceIme
        }
        val builtInVoiceModel = enumList(
            R.string.voice_model_preference,
            "built_in_voice_model",
            VoiceModelPreference.Balanced
        ) {
            showVoiceInputButton.getValue() && voiceInputMode.getValue() == VoiceInputMode.BuiltInSpeechRecognizer
        }

        val expandKeypressArea =
            switch(R.string.expand_keypress_area, "expand_keypress_area", false)
        val swipeSymbolDirection = enumList(
            R.string.swipe_symbol_behavior,
            "swipe_symbol_behavior",
            SwipeSymbolDirection.Down
        )
        val longPressDelay = int(
            R.string.keyboard_long_press_delay,
            "keyboard_long_press_delay",
            300,
            100,
            700,
            "ms",
            10
        )
        val spaceKeyLongPressBehavior = enumList(
            R.string.space_long_press_behavior,
            "space_long_press_behavior",
            SpaceLongPressBehavior.None
        )
        val spaceSwipeMoveCursor =
            switch(R.string.space_swipe_move_cursor, "space_swipe_move_cursor", true)
        val showLangSwitchKey =
            switch(R.string.show_lang_switch_key, "show_lang_switch_key", true)
        val langSwitchKeyBehavior = enumList(
            R.string.lang_switch_key_behavior,
            "lang_switch_key_behavior",
            LangSwitchBehavior.Enumerate
        ) { showLangSwitchKey.getValue() }

        val keyboardHeightPercent: ManagedPreference.PInt
        val keyboardHeightPercentLandscape: ManagedPreference.PInt

        init {
            val (primary, secondary) = twinInt(
                R.string.keyboard_height,
                R.string.portrait,
                "keyboard_height_percent",
                30,
                R.string.landscape,
                "keyboard_height_percent_landscape",
                49,
                10,
                90,
                "%"
            )
            keyboardHeightPercent = primary
            keyboardHeightPercentLandscape = secondary
        }

        val keyboardSidePadding: ManagedPreference.PInt
        val keyboardSidePaddingLandscape: ManagedPreference.PInt

        init {
            val (primary, secondary) = twinInt(
                R.string.keyboard_side_padding,
                R.string.portrait,
                "keyboard_side_padding",
                0,
                R.string.landscape,
                "keyboard_side_padding_landscape",
                0,
                0,
                300,
                "dp"
            )
            keyboardSidePadding = primary
            keyboardSidePaddingLandscape = secondary
        }

        val keyboardBottomPadding: ManagedPreference.PInt
        val keyboardBottomPaddingLandscape: ManagedPreference.PInt

        init {
            val (primary, secondary) = twinInt(
                R.string.keyboard_bottom_padding,
                R.string.portrait,
                "keyboard_bottom_padding",
                0,
                R.string.landscape,
                "keyboard_bottom_padding_landscape",
                0,
                0,
                100,
                "dp"
            )
            keyboardBottomPadding = primary
            keyboardBottomPaddingLandscape = secondary
        }

        val horizontalCandidateStyle = enumList(
            R.string.horizontal_candidate_style,
            "horizontal_candidate_style",
            HorizontalCandidateMode.AutoFillWidth
        )
        val expandedCandidateStyle = enumList(
            R.string.expanded_candidate_style,
            "expanded_candidate_style",
            ExpandedCandidateStyle.Grid
        )

        val expandedCandidateGridSpanCount: ManagedPreference.PInt
        val expandedCandidateGridSpanCountLandscape: ManagedPreference.PInt

        init {
            val (primary, secondary) = twinInt(
                R.string.expanded_candidate_grid_span_count,
                R.string.portrait,
                "expanded_candidate_grid_span_count_portrait",
                6,
                R.string.landscape,
                "expanded_candidate_grid_span_count_landscape",
                8,
                4,
                12,
            )
            expandedCandidateGridSpanCount = primary
            expandedCandidateGridSpanCountLandscape = secondary
        }

    }

    inner class Candidates :
        ManagedPreferenceCategory(R.string.candidates_window, sharedPreferences) {
        val mode = enumList(
            R.string.show_candidates_window,
            "show_candidates_window",
            FloatingCandidatesMode.InputDevice
        )

        val orientation = enumList(
            R.string.candidates_orientation,
            "candidates_window_orientation",
            FloatingCandidatesOrientation.Automatic
        )

        val windowMinWidth = int(
            R.string.candidates_window_min_width,
            "candidates_window_min_width",
            0,
            0,
            640,
            "dp",
            10
        )

        val windowPadding =
            int(R.string.candidates_window_padding, "candidates_window_padding", 4, 0, 32, "dp")

        val fontSize =
            int(R.string.candidates_font_size, "candidates_window_font_size", 20, 4, 64, "sp")

        val windowRadius =
            int(R.string.candidates_window_radius, "candidates_window_radius", 0, 0, 48, "dp")

        val itemPaddingVertical: ManagedPreference.PInt
        val itemPaddingHorizontal: ManagedPreference.PInt

        init {
            val (primary, secondary) = twinInt(
                R.string.candidates_padding,
                R.string.vertical,
                "candidates_item_padding_vertical",
                2,
                R.string.horizontal,
                "candidates_item_padding_horizontal",
                4,
                0,
                64,
                "dp"
            )
            itemPaddingVertical = primary
            itemPaddingHorizontal = secondary
        }
    }

    inner class Clipboard : ManagedPreferenceCategory(R.string.clipboard, sharedPreferences) {
        val clipboardListening = switch(R.string.clipboard_listening, "clipboard_enable", true)
        val clipboardHistoryLimit = int(
            R.string.clipboard_limit,
            "clipboard_limit",
            10,
        ) { clipboardListening.getValue() }
        val clipboardSuggestion = switch(
            R.string.clipboard_suggestion, "clipboard_suggestion", true
        ) { clipboardListening.getValue() }
        val clipboardItemTimeout = int(
            R.string.clipboard_suggestion_timeout,
            "clipboard_item_timeout",
            30,
            -1,
            Int.MAX_VALUE,
            "s"
        ) { clipboardListening.getValue() && clipboardSuggestion.getValue() }
        val clipboardReturnAfterPaste = switch(
            R.string.clipboard_return_after_paste, "clipboard_return_after_paste", false
        ) { clipboardListening.getValue() }
        val clipboardMaskSensitive = switch(
            R.string.clipboard_mask_sensitive, "clipboard_mask_sensitive", true
        ) { clipboardListening.getValue() }
    }

    inner class Ai : ManagedPreferenceCategory(R.string.ai, sharedPreferences) {
        val chatEnabled = switch(
            R.string.ai_chat_enabled,
            "ai_chat_enabled",
            false,
            R.string.ai_chat_enabled_summary
        )
        val chatBaseUrl = string(
            R.string.ai_chat_base_url,
            "ai_chat_base_url",
            "",
            R.string.ai_chat_base_url_summary
        ) { chatEnabled.getValue() }
        val chatApiKey = string(
            R.string.ai_chat_api_key,
            "ai_chat_api_key",
            "",
            R.string.ai_chat_api_key_summary
        ) { chatEnabled.getValue() }
        val chatModel = string(
            R.string.ai_chat_model,
            "ai_chat_model",
            "",
            R.string.ai_chat_model_summary
        ) { chatEnabled.getValue() }
        val chatTimeoutSeconds = int(
            R.string.ai_chat_timeout_seconds,
            "ai_chat_timeout_seconds",
            20,
            1,
            300,
            "s"
        ) { chatEnabled.getValue() }

        val functionKitRemoteInferenceEnabled = switch(
            R.string.function_kit_remote_inference_enabled,
            "function_kit_remote_inference_enabled",
            false,
            R.string.function_kit_remote_inference_enabled_summary
        )
        val functionKitRemoteBaseUrl = string(
            R.string.function_kit_remote_base_url,
            "function_kit_remote_base_url",
            "http://127.0.0.1:18789",
            R.string.function_kit_remote_base_url_summary
        ) { functionKitRemoteInferenceEnabled.getValue() }
        val functionKitRemoteAuthToken = string(
            R.string.function_kit_remote_auth_token,
            "function_kit_remote_auth_token",
            "",
            R.string.function_kit_remote_auth_token_summary
        ) { functionKitRemoteInferenceEnabled.getValue() }
        val functionKitRemoteTimeoutSeconds = int(
            R.string.function_kit_remote_timeout_seconds,
            "function_kit_remote_timeout_seconds",
            20,
            1,
            300,
            "s"
        ) { functionKitRemoteInferenceEnabled.getValue() }

        // Function Kit integrations related to AI are configured in the AI page (not Function Kit settings).
        // Keep using the existing preference keys so the runtime permission policy stays consistent.
        val allowFunctionKitAiChat = switch(
            R.string.function_kit_permission_ai_chat,
            "function_kit_permission_ai_chat",
            true
        )
        val allowDesktopAgentAccess = switch(
            R.string.function_kit_permission_ai_agent_access,
            "function_kit_permission_ai_agent_access",
            true
        )
    }

    inner class FunctionKit : ManagedPreferenceCategory(R.string.function_kit, sharedPreferences) {
        val showToolbarButton = switch(
            R.string.function_kit_toolbar_button,
            "function_kit_toolbar_button",
            true,
            R.string.function_kit_toolbar_button_summary
        )
        val remoteInferenceEnabled = switch(
            R.string.function_kit_remote_inference_enabled,
            "function_kit_remote_inference_enabled",
            false,
            R.string.function_kit_remote_inference_enabled_summary
        )
        val remoteBaseUrl = string(
            R.string.function_kit_remote_base_url,
            "function_kit_remote_base_url",
            "http://127.0.0.1:18789",
            R.string.function_kit_remote_base_url_summary
        ) { remoteInferenceEnabled.getValue() }
        val remoteAuthToken = string(
            R.string.function_kit_remote_auth_token,
            "function_kit_remote_auth_token",
            "",
            R.string.function_kit_remote_auth_token_summary
        ) { remoteInferenceEnabled.getValue() }
        val remoteTimeoutSeconds = int(
            R.string.function_kit_remote_timeout_seconds,
            "function_kit_remote_timeout_seconds",
            20,
            1,
            300,
            "s"
        ) { remoteInferenceEnabled.getValue() }
        val kitStudioAttachEnabled = switch(
            R.string.function_kit_kitstudio_attach_enabled,
            "function_kit_kitstudio_attach_enabled",
            false,
            R.string.function_kit_kitstudio_attach_enabled_summary
        )
        val kitStudioAttachBaseUrl = string(
            R.string.function_kit_kitstudio_attach_base_url,
            "function_kit_kitstudio_attach_base_url",
            "http://127.0.0.1:39001",
            R.string.function_kit_kitstudio_attach_base_url_summary
        ) { kitStudioAttachEnabled.getValue() }
        val kitStudioAttachToken = string(
            R.string.function_kit_kitstudio_attach_token,
            "function_kit_kitstudio_attach_token",
            "",
            R.string.function_kit_kitstudio_attach_token_summary
        ) { kitStudioAttachEnabled.getValue() }
        val allowContextRead = switch(
            R.string.function_kit_permission_context_read,
            "function_kit_permission_context_read",
            true
        )
        val allowInputInsert = switch(
            R.string.function_kit_permission_input_insert,
            "function_kit_permission_input_insert",
            true
        )
        val allowInputReplace = switch(
            R.string.function_kit_permission_input_replace,
            "function_kit_permission_input_replace",
            true
        )
        val allowInputCommitImage = switch(
            R.string.function_kit_permission_input_commit_image,
            "function_kit_permission_input_commit_image",
            true
        )
        val allowInputObserveBestEffort = switch(
            R.string.function_kit_permission_input_observe_best_effort,
            "function_kit_permission_input_observe_best_effort",
            true
        )
        val allowCandidatesRegenerate = switch(
            R.string.function_kit_permission_candidates_regenerate,
            "function_kit_permission_candidates_regenerate",
            true
        )
        val allowSendInterceptImeAction = switch(
            R.string.function_kit_permission_send_intercept_ime_action,
            "function_kit_permission_send_intercept_ime_action",
            true
        )
        val allowNetworkFetch = switch(
            R.string.function_kit_permission_network_fetch,
            "function_kit_permission_network_fetch",
            true
        )
        val allowAiChat = switch(
            R.string.function_kit_permission_ai_chat,
            "function_kit_permission_ai_chat",
            true
        )
        val allowAiAgentAccess = switch(
            R.string.function_kit_permission_ai_agent_access,
            "function_kit_permission_ai_agent_access",
            true
        )
        val allowSettingsOpen = switch(
            R.string.function_kit_permission_settings_open,
            "function_kit_permission_settings_open",
            true
        )
        val allowStorageRead = switch(
            R.string.function_kit_permission_storage_read,
            "function_kit_permission_storage_read",
            true
        )
        val allowStorageWrite = switch(
            R.string.function_kit_permission_storage_write,
            "function_kit_permission_storage_write",
            true
        )
        val allowFilesPick = switch(
            R.string.function_kit_permission_files_pick,
            "function_kit_permission_files_pick",
            true
        )
        val allowPanelStateWrite = switch(
            R.string.function_kit_permission_panel_state_write,
            "function_kit_permission_panel_state_write",
            true
        )
        val allowRuntimeMessageSend = switch(
            R.string.function_kit_permission_runtime_message_send,
            "function_kit_permission_runtime_message_send",
            true
        )
        val allowRuntimeMessageReceive = switch(
            R.string.function_kit_permission_runtime_message_receive,
            "function_kit_permission_runtime_message_receive",
            true
        )
    }

    inner class Symbols : ManagedPreferenceCategory(R.string.emoji_and_symbols, sharedPreferences) {
        val hideUnsupportedEmojis = switch(
            R.string.hide_unsupported_emojis,
            "hide_unsupported_emojis",
            true
        )

        val defaultEmojiSkinTone = enumList(
            R.string.default_emoji_skin_tone,
            "default_emoji_skin_tone",
            EmojiModifier.SkinTone.Default,
        )
    }

    private val providers = mutableListOf<ManagedPreferenceProvider>()

    fun <T : ManagedPreferenceProvider> registerProvider(
        providerF: (SharedPreferences) -> T
    ): T {
        val provider = providerF(sharedPreferences)
        providers.add(provider)
        return provider
    }

    private fun <T : ManagedPreferenceProvider> T.register() = this.apply {
        registerProvider { this }
    }

    val internal = Internal().register()
    val keyboard = Keyboard().register()
    val candidates = Candidates().register()
    val clipboard = Clipboard().register()
    val ai = Ai().register()
    val functionKit = FunctionKit().register()
    val symbols = Symbols().register()
    val advanced = Advanced().register()

    @Keep
    private val onSharedPreferenceChangeListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null) return@OnSharedPreferenceChangeListener
            providers.forEach {
                it.fireChange(key)
            }
        }

    @RequiresApi(Build.VERSION_CODES.N)
    fun syncToDeviceEncryptedStorage() {
        val ctx = appContext.createDeviceProtectedStorageContext()
        val sp = PreferenceManager.getDefaultSharedPreferences(ctx)
        sp.edit {
            listOf(
                internal.verboseLog,
                internal.editorInfoInspector,
                advanced.ignoreSystemCursor,
                advanced.disableAnimation,
                advanced.vivoKeypressWorkaround
            ).forEach {
                it.putValueTo(this@edit)
            }
            listOf(
                keyboard,
                candidates,
                clipboard,
                ai,
                functionKit
            ).forEach { category ->
                category.managedPreferences.forEach {
                    it.value.putValueTo(this@edit)
                }
            }
        }
    }

    private fun applyMigrations() {
        migrateFunctionKitToolbarShortcutDefaultOn()
        migrateVoiceSpaceLongPressDefaultOn()
    }

    private fun migrateFunctionKitToolbarShortcutDefaultOn() {
        if (internal.migratedFunctionKitToolbarShortcutDefaultOn.getValue()) {
            return
        }

        if (!functionKit.showToolbarButton.getValue()) {
            functionKit.showToolbarButton.setValue(true)
        }

        internal.migratedFunctionKitToolbarShortcutDefaultOn.setValue(true)
    }

    private fun migrateVoiceSpaceLongPressDefaultOn() {
        if (internal.migratedVoiceSpaceLongPressDefaultOn.getValue()) {
            return
        }

        if (!sharedPreferences.contains("space_long_press_behavior") && keyboard.showVoiceInputButton.getValue()) {
            keyboard.spaceKeyLongPressBehavior.setValue(SpaceLongPressBehavior.VoiceInput)
        }

        internal.migratedVoiceSpaceLongPressDefaultOn.setValue(true)
    }

    companion object {
        private var instance: AppPrefs? = null

        /**
         * MUST call before use
         */
        fun init(sharedPreferences: SharedPreferences) {
            if (instance != null) {
                return
            }
            val prefs = AppPrefs(sharedPreferences)
            prefs.applyMigrations()
            instance = prefs
            sharedPreferences.registerOnSharedPreferenceChangeListener(prefs.onSharedPreferenceChangeListener)
        }

        fun getInstance() = instance!!
    }
}

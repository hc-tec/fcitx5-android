/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar

import android.graphics.Color
import android.os.Build
import android.util.Size
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InlineSuggestion
import android.view.inputmethod.InlineSuggestionsResponse
import android.view.inputmethod.InputMethodSubtype
import android.widget.FrameLayout
import android.widget.ViewAnimator
import android.widget.inline.InlineContentView
import android.widget.PopupMenu
import androidx.annotation.Keep
import androidx.annotation.RequiresApi
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.CapabilityFlag
import org.fcitx.fcitx5.android.core.CapabilityFlags
import org.fcitx.fcitx5.android.core.FcitxEvent.CandidateListEvent
import org.fcitx.fcitx5.android.data.InputFeedbacks
import org.fcitx.fcitx5.android.data.clipboard.ClipboardManager
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardEntry
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.bar.ExpandButtonStateMachine.State.ClickToAttachWindow
import org.fcitx.fcitx5.android.input.bar.ExpandButtonStateMachine.State.ClickToDetachWindow
import org.fcitx.fcitx5.android.input.bar.ExpandButtonStateMachine.State.Hidden
import org.fcitx.fcitx5.android.input.bar.KawaiiBarStateMachine.BooleanKey.CandidateEmpty
import org.fcitx.fcitx5.android.input.bar.KawaiiBarStateMachine.BooleanKey.PreeditEmpty
import org.fcitx.fcitx5.android.input.bar.KawaiiBarStateMachine.TransitionEvent.CandidatesUpdated
import org.fcitx.fcitx5.android.input.bar.KawaiiBarStateMachine.TransitionEvent.ExtendedWindowAttached
import org.fcitx.fcitx5.android.input.bar.KawaiiBarStateMachine.TransitionEvent.PreeditUpdated
import org.fcitx.fcitx5.android.input.bar.KawaiiBarStateMachine.TransitionEvent.WindowDetached
import org.fcitx.fcitx5.android.input.bar.ui.CandidateUi
import org.fcitx.fcitx5.android.input.bar.ui.IdleUi
import org.fcitx.fcitx5.android.input.bar.ui.TitleUi
import org.fcitx.fcitx5.android.input.bar.ui.idle.ButtonsBarUi
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcastReceiver
import org.fcitx.fcitx5.android.input.candidates.expanded.ExpandedCandidateStyle
import org.fcitx.fcitx5.android.input.candidates.expanded.window.FlexboxExpandedCandidateWindow
import org.fcitx.fcitx5.android.input.candidates.expanded.window.GridExpandedCandidateWindow
import org.fcitx.fcitx5.android.input.candidates.horizontal.HorizontalCandidateComponent
import org.fcitx.fcitx5.android.input.clipboard.ClipboardWindow
import org.fcitx.fcitx5.android.input.dependency.UniqueViewComponent
import org.fcitx.fcitx5.android.input.dependency.context
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.dependency.theme
import org.fcitx.fcitx5.android.input.editing.TextEditingWindow
import org.fcitx.fcitx5.android.input.functionkit.FunctionKitBindingRegistry
import org.fcitx.fcitx5.android.input.functionkit.FunctionKitBindingTrigger
import org.fcitx.fcitx5.android.input.functionkit.FunctionKitBindingsWindow
import org.fcitx.fcitx5.android.input.functionkit.FunctionKitKitSettings
import org.fcitx.fcitx5.android.input.functionkit.FunctionKitQuickAccessOrderer
import org.fcitx.fcitx5.android.input.functionkit.FunctionKitRegistry
import org.fcitx.fcitx5.android.input.functionkit.FunctionKitTaskCenterWindow
import org.fcitx.fcitx5.android.input.functionkit.FunctionKitTaskHub
import org.fcitx.fcitx5.android.input.functionkit.FunctionKitWindowPool
import org.fcitx.fcitx5.android.input.functionkit.FunctionKitWindow
import org.fcitx.fcitx5.android.input.keyboard.CommonKeyActionListener
import org.fcitx.fcitx5.android.input.keyboard.CustomGestureView
import org.fcitx.fcitx5.android.input.keyboard.KeyboardWindow
import org.fcitx.fcitx5.android.input.popup.PopupComponent
import org.fcitx.fcitx5.android.input.status.StatusAreaWindow
import org.fcitx.fcitx5.android.input.wm.InputWindow
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.fcitx.fcitx5.android.utils.AppUtil
import org.fcitx.fcitx5.android.utils.InputMethodUtil
import org.mechdancer.dependency.DynamicScope
import org.mechdancer.dependency.manager.must
import splitties.bitflags.hasFlag
import splitties.dimensions.dp
import splitties.views.backgroundColor
import splitties.views.dsl.core.add
import splitties.views.dsl.core.frameLayout
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.PI
import kotlin.math.sin

class KawaiiBarComponent : UniqueViewComponent<KawaiiBarComponent, FrameLayout>(),
    InputBroadcastReceiver {

    private val context by manager.context()
    private val theme by manager.theme()
    private val service by manager.inputMethodService()
    private val windowManager: InputWindowManager by manager.must()
    private val horizontalCandidate: HorizontalCandidateComponent by manager.must()
    private val commonKeyActionListener: CommonKeyActionListener by manager.must()
    private val popup: PopupComponent by manager.must()

    private val prefs = AppPrefs.getInstance()

    private val clipboardSuggestion = prefs.clipboard.clipboardSuggestion
    private val clipboardItemTimeout = prefs.clipboard.clipboardItemTimeout
    private val clipboardMaskSensitive by prefs.clipboard.clipboardMaskSensitive
    private val expandedCandidateStyle by prefs.keyboard.expandedCandidateStyle
    private val expandToolbarByDefault by prefs.keyboard.expandToolbarByDefault
    private val functionKitToolbarButton = prefs.functionKit.showToolbarButton
    private val showFunctionKitToolbarButton by functionKitToolbarButton
    private val toolbarNumRowOnPassword by prefs.keyboard.toolbarNumRowOnPassword
    private val showVoiceInputButton by prefs.keyboard.showVoiceInputButton
    private val preferredVoiceInput by prefs.keyboard.preferredVoiceInput

    private var clipboardTimeoutJob: Job? = null

    private var isClipboardFresh: Boolean = false
    private var isInlineSuggestionPresent: Boolean = false
    private var isCapabilityFlagsPassword: Boolean = false
    private var isKeyboardLayoutNumber: Boolean = false
    private var isToolbarManuallyToggled: Boolean = false

    private var removeTaskCenterListener: (() -> Unit)? = null
    private var removeFunctionKitKitSettingsListener: (() -> Unit)? = null
    private var functionKitToolbarRefreshPending: Boolean = false
    private var functionKitQuickAccessMenu: PopupMenu? = null

    private val functionKitWindowPool: FunctionKitWindowPool by manager.must()

    private fun requireFunctionKitWindow(kitId: String): FunctionKitWindow =
        functionKitWindowPool.require(kitId)

    private enum class NumberRowState { Auto, ForceShow, ForceHide }

    private var numberRowState = NumberRowState.Auto

    private fun buildToolbarFunctionKitEntries(): List<ButtonsBarUi.FunctionKitToolbarButtonEntry> {
        val installed =
            FunctionKitRegistry.listInstalled(context)
                .ifEmpty { listOf(FunctionKitRegistry.resolve(context)) }
        val enabled =
            installed.filter { kit -> FunctionKitKitSettings.isKitEnabled(kit.id) }

        val pinnedKitIds =
            enabled.mapNotNull { kit ->
                kit.id.takeIf { FunctionKitKitSettings.isKitPinned(it) }
            }.toSet()
        val lastUsedAtEpochMsByKitId =
            enabled.associate { kit ->
                kit.id to FunctionKitKitSettings.lastUsedAtEpochMs(kit.id)
            }
        val kitById = enabled.associateBy { it.id }
        val orderedKitIds =
            FunctionKitQuickAccessOrderer.orderKitIds(
                kitIds = enabled.map { it.id },
                pinnedKitIds = pinnedKitIds,
                lastUsedAtEpochMsByKitId = lastUsedAtEpochMsByKitId
            )

        return orderedKitIds.mapNotNull { kitById[it] }.map { kit ->
                org.fcitx.fcitx5.android.input.bar.ui.idle.ButtonsBarUi.FunctionKitToolbarButtonEntry(
                    kitId = kit.id,
                    label = FunctionKitRegistry.displayName(context, kit),
                    iconAssetPath = kit.preferredIconAssetPath(128)
                )
            }
    }

    private fun bindFunctionKitButtons(buttonsUi: ButtonsBarUi) {
        buttonsUi.functionKitButtons.forEach { kitButton ->
            kitButton.button.setOnClickListener {
                val window = requireFunctionKitWindow(kitButton.entry.kitId)
                windowManager.view.post { windowManager.attachWindow(window) }
            }
            kitButton.button.setOnLongClickListener { view ->
                val kitId = kitButton.entry.kitId
                val kitLabel = kitButton.entry.label

                InputFeedbacks.hapticFeedback(view, longPress = true)
                functionKitQuickAccessMenu?.dismiss()

                val itemHeader = 1
                val itemOpenOptions = 2
                val itemTogglePinned = 3
                val itemManagePermissions = 4
                functionKitQuickAccessMenu =
                    PopupMenu(context, view).apply {
                        val pinned = FunctionKitKitSettings.isKitPinned(kitId)

                        menu.add(0, itemHeader, 0, kitLabel).apply { isEnabled = false }
                        menu.add(0, itemOpenOptions, 10, R.string.function_kit_quick_access_menu_open_options)
                        menu.add(
                            0,
                            itemTogglePinned,
                            20,
                            if (pinned) {
                                R.string.function_kit_quick_access_menu_unpin_from_toolbar
                            } else {
                                R.string.function_kit_manager_pin_to_toolbar
                            }
                        )
                        menu.add(
                            0,
                            itemManagePermissions,
                            30,
                            R.string.function_kit_quick_access_menu_manage_permissions
                        )

                        setOnMenuItemClickListener { item ->
                            when (item.itemId) {
                                itemOpenOptions -> {
                                    val window = requireFunctionKitWindow(kitId)
                                    window.requestOpenOptions()
                                    windowManager.view.post { windowManager.attachWindow(window) }
                                    true
                                }
                                itemTogglePinned -> {
                                    FunctionKitKitSettings.setKitPinned(kitId, !pinned)
                                    true
                                }
                                itemManagePermissions -> {
                                    AppUtil.launchMainToFunctionKitDetail(context, kitId)
                                    true
                                }
                                else -> false
                            }
                        }

                        setOnDismissListener { functionKitQuickAccessMenu = null }
                        show()
                    }
                true
            }
        }
    }

    private fun refreshFunctionKitToolbarButtons() {
        idleUi.buttonsUi.setFunctionKitEntries(buildToolbarFunctionKitEntries())
        bindFunctionKitButtons(idleUi.buttonsUi)
        updateFunctionKitToolbarButtonVisibility()
    }

    private fun requestRefreshFunctionKitToolbarButtons() {
        if (functionKitToolbarRefreshPending) return
        functionKitToolbarRefreshPending = true
        windowManager.view.post {
            functionKitToolbarRefreshPending = false
            refreshFunctionKitToolbarButtons()
        }
    }

    private val clipboardFunctionKitBindings by lazy {
        FunctionKitBindingRegistry.listForTrigger(context, FunctionKitBindingTrigger.Clipboard)
    }

    @Keep
    private val onClipboardUpdateListener =
        ClipboardManager.OnClipboardUpdateListener {
            if (!clipboardSuggestion.getValue()) return@OnClipboardUpdateListener
            service.lifecycleScope.launch {
                if (it.text.isEmpty()) {
                    isClipboardFresh = false
                } else {
                    idleUi.clipboardUi.text.text = if (it.sensitive && clipboardMaskSensitive) {
                        ClipboardEntry.BULLET.repeat(min(42, it.text.length))
                    } else {
                        it.text.take(42)
                    }
                    isClipboardFresh = true
                    launchClipboardTimeoutJob()
                }
                evalIdleUiState()
            }
        }

    @Keep
    private val onClipboardSuggestionUpdateListener =
        ManagedPreference.OnChangeListener<Boolean> { _, it ->
            if (!it) {
                isClipboardFresh = false
                evalIdleUiState()
                clipboardTimeoutJob?.cancel()
                clipboardTimeoutJob = null
            }
        }

    @Keep
    private val onClipboardTimeoutUpdateListener =
        ManagedPreference.OnChangeListener<Int> { _, _ ->
            when (idleUi.currentState) {
                IdleUi.State.Clipboard -> {
                    // renew timeout when clipboard suggestion is present
                    launchClipboardTimeoutJob()
                }
                else -> {}
            }
        }

    @Keep
    private val onFunctionKitToolbarButtonChangeListener =
        ManagedPreference.OnChangeListener<Boolean> { _, _ ->
            updateFunctionKitToolbarButtonVisibility()
        }

    private fun launchClipboardTimeoutJob() {
        clipboardTimeoutJob?.cancel()
        val timeout = clipboardItemTimeout.getValue() * 1000L
        // never transition to ClipboardTimedOut state when timeout < 0
        if (timeout < 0L) return
        clipboardTimeoutJob = service.lifecycleScope.launch {
            delay(timeout)
            isClipboardFresh = false
            clipboardTimeoutJob = null
        }
    }

    private fun updateFunctionKitToolbarButtonVisibility() {
        val visibility = if (showFunctionKitToolbarButton) View.VISIBLE else View.GONE
        idleUi.buttonsUi.functionKitButtons.forEach {
            it.button.visibility = visibility
        }
    }

    private fun dismissClipboardSuggestion() {
        clipboardTimeoutJob?.cancel()
        clipboardTimeoutJob = null
        isClipboardFresh = false
        evalIdleUiState()
    }

    private fun evalIdleUiState(fromUser: Boolean = false) {
        val newState = when {
            numberRowState == NumberRowState.ForceShow -> IdleUi.State.NumberRow
            isClipboardFresh -> IdleUi.State.Clipboard
            isInlineSuggestionPresent -> IdleUi.State.InlineSuggestion
            isCapabilityFlagsPassword && !isKeyboardLayoutNumber && numberRowState != NumberRowState.ForceHide -> IdleUi.State.NumberRow
            /**
             * state matrix:
             *                               expandToolbarByDefault
             *                          |   \   |    true |   false
             * isToolbarManuallyToggled |  true |   Empty | Toolbar
             *                          | false | Toolbar |   Empty
             */
            expandToolbarByDefault == isToolbarManuallyToggled -> IdleUi.State.Empty
            else -> IdleUi.State.Toolbar
        }
        if (newState == idleUi.currentState) return
        idleUi.updateState(newState, fromUser)
    }

    private val hideKeyboardCallback = View.OnClickListener {
        service.requestHideSelf(0)
    }

    private val swipeDownExpandCallback = CustomGestureView.OnGestureListener { _, e ->
        if (e.type == CustomGestureView.GestureType.Up && e.totalY > 0) {
            service.requestHideSelf(0)
            true
        } else false
    }

    // Combined gesture: determine primary direction by comparing totalX and totalY.
    // - If horizontal is dominant and left, show number row (when allowed).
    // - If vertical is dominant and down, hide keyboard.
    private val swipeHideKeyboardCallback = CustomGestureView.OnGestureListener { v, e ->
        val numberRowAvailable = isCapabilityFlagsPassword && !isKeyboardLayoutNumber
        if (numberRowAvailable) {
            val dir = if (context.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_LTR) 1 else -1
            // We can't access the rawX and rawY of the MotionEvent, so we need to do some math.
            // `e.x` and `e.y` are relative to the view's top-left corner, we want to rotate
            // around the center of the view, so we translate them to be relative to the center
            val relX = e.x - v.width / 2f
            val relY = e.y - v.height / 2f

            // rotate the relative coordinates by current rotation to get absolute coordinates
            // the button is ↓, so apply -90 degrees offset
            val theta = Math.toRadians(v.rotation.toDouble()) - PI / 2
            val c = cos(theta)
            val s = sin(theta)
            val screenX = c * relX - s * relY
            val screenY = s * relX + c * relY
            val distance = hypot(screenX, screenY)
            var angle = Math.toDegrees(atan2(screenY, screenX)).toFloat()

            when (e.type) {
                CustomGestureView.GestureType.Move -> {
                    angle = if (angle in -45f..45f) {
                        angle.coerceIn(-10f, 10f)
                    } else abs(angle).coerceIn(90f - 10f, 90f + 10f) * dir
                    v.rotation = angle
                }
                CustomGestureView.GestureType.Up -> {
                    val thresholdX = (v as CustomGestureView).swipeThresholdX
                    val thresholdY = v.swipeThresholdY
                    val handled = when (angle) {
                        in -45f..45f if distance > thresholdY -> {
                            service.requestHideSelf(0)
                            true
                        }
                        !in -45f..45f if distance > thresholdX -> {
                            v.rotation = 90f * dir
                            numberRowState = NumberRowState.ForceShow
                            evalIdleUiState(fromUser = true)
                            true
                        }
                        else -> false
                    }
                    v.rotation = 0f
                    return@OnGestureListener handled
                }
                else -> {}
            }
        }

        if (e.type == CustomGestureView.GestureType.Up && abs(e.totalY) > abs(e.totalX) && e.totalY > 0) {
            service.requestHideSelf(0)
            true
        } else false
    }

    private var voiceInputSubtype: Pair<String, InputMethodSubtype>? = null

    private val switchToVoiceInputCallback = View.OnClickListener {
        val (id, subtype) = voiceInputSubtype ?: return@OnClickListener
        InputMethodUtil.switchInputMethod(service, id, subtype)
    }

    private val idleUi: IdleUi by lazy {
        IdleUi(context, theme, popup, commonKeyActionListener, buildToolbarFunctionKitEntries()).apply {
            menuButton.setOnClickListener {
                when (idleUi.currentState) {
                    IdleUi.State.Empty -> {
                        isToolbarManuallyToggled = !expandToolbarByDefault
                        evalIdleUiState(fromUser = true)
                    }
                    IdleUi.State.Toolbar -> {
                        isToolbarManuallyToggled = expandToolbarByDefault
                        evalIdleUiState(fromUser = true)
                    }
                    else -> {
                        isToolbarManuallyToggled = !expandToolbarByDefault
                        idleUi.updateState(IdleUi.State.Toolbar, fromUser = true)
                    }
                }
                // reset timeout timer (if present) when user switch layout
                if (clipboardTimeoutJob != null) {
                    launchClipboardTimeoutJob()
                }
            }
            hideKeyboardButton.apply {
                setOnClickListener(hideKeyboardCallback)
                swipeEnabled = true
                swipeThresholdY = dp(HEIGHT.toFloat())
                swipeThresholdX = swipeThresholdY
                onGestureListener = swipeHideKeyboardCallback
            }
            buttonsUi.apply {
                undoButton.setOnClickListener {
                    service.sendCombinationKeyEvents(KeyEvent.KEYCODE_Z, ctrl = true)
                }
                redoButton.setOnClickListener {
                    service.sendCombinationKeyEvents(KeyEvent.KEYCODE_Z, ctrl = true, shift = true)
                }
                cursorMoveButton.setOnClickListener {
                    windowManager.attachWindow(TextEditingWindow())
                }
                clipboardButton.setOnClickListener {
                    windowManager.attachWindow(ClipboardWindow())
                }
                bindingsButton.setOnClickListener {
                    windowManager.attachWindow(FunctionKitBindingsWindow())
                }
                taskCenterButton.setOnClickListener {
                    windowManager.attachWindow(FunctionKitTaskCenterWindow())
                }
                bindFunctionKitButtons(this)
                moreButton.setOnClickListener {
                    windowManager.attachWindow(StatusAreaWindow())
                }
            }
            clipboardUi.suggestionView.apply {
                setOnClickListener {
                    val clipboardText = ClipboardManager.lastEntry?.text.orEmpty()
                    if (clipboardText.isBlank()) {
                        dismissClipboardSuggestion()
                        return@setOnClickListener
                    }

                    if (clipboardFunctionKitBindings.isEmpty()) {
                        service.commitText(clipboardText)
                        dismissClipboardSuggestion()
                        return@setOnClickListener
                    }

                    windowManager.view.post {
                        windowManager.attachWindow(
                            FunctionKitBindingsWindow(
                                trigger = FunctionKitBindingTrigger.Clipboard,
                                clipboardText = clipboardText
                            )
                        )
                    }
                    dismissClipboardSuggestion()
                }
                setOnLongClickListener {
                    ClipboardManager.lastEntry?.let {
                        AppUtil.launchClipboardEdit(context, it.id, true)
                    }
                    true
                }
            }
            numberRow.apply {
                onCollapseListener = {
                    numberRowState = NumberRowState.ForceHide
                    evalIdleUiState(fromUser = true)
                }
            }
        }
    }

    private val candidateUi by lazy {
        CandidateUi(context, theme, horizontalCandidate.view).apply {
            expandButton.apply {
                swipeEnabled = true
                swipeThresholdY = dp(HEIGHT.toFloat())
                onGestureListener = swipeDownExpandCallback
            }
        }
    }

    private val candidateSlot by lazy {
        context.frameLayout {
            add(
                candidateUi.root,
                lParams(matchParent, matchParent)
            )
        }
    }

    private val titleUi by lazy {
        TitleUi(context, theme)
    }

    private val barStateMachine = KawaiiBarStateMachine.new {
        switchUiByState(it)
    }

    private var activeExtendedWindow: InputWindow.ExtendedInputWindow<*>? = null
    private var functionKitEmbeddedComposerActive: Boolean = false
    private var functionKitCandidateDockContainer: ViewGroup? = null
    private var functionKitCandidateDockActive: Boolean = false
    private var isPreeditEmpty: Boolean = true
    private var isCandidateEmpty: Boolean = true

    internal fun setFunctionKitCandidateDock(container: ViewGroup?) {
        if (functionKitCandidateDockContainer === container) {
            return
        }
        functionKitCandidateDockContainer = container
        functionKitCandidateDockActive = container != null

        val candidateRoot = candidateUi.root
        (candidateRoot.parent as? ViewGroup)?.removeView(candidateRoot)

        val targetParent = container ?: candidateSlot
        targetParent.addView(
            candidateRoot,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        syncExpandButtonState()
    }

    private fun syncExpandButtonState() {
        val enabled = expandButtonStateMachine.currentState != Hidden
        setExpandButtonEnabled(enabled)
    }

    internal fun setFunctionKitEmbeddedComposerActive(active: Boolean) {
        if (functionKitEmbeddedComposerActive == active) {
            return
        }
        functionKitEmbeddedComposerActive = active

        val attachedWindow = activeExtendedWindow
        if (attachedWindow !is FunctionKitWindow) {
            return
        }

        if (!active) {
            // When the embedded composer is closed, we should always fall back to the Title UI for
            // the attached Function Kit window (instead of staying in Candidate state).
            if (barStateMachine.currentState != KawaiiBarStateMachine.State.Title) {
                barStateMachine.unsafeJump(KawaiiBarStateMachine.State.Title)
            }
            return
        }

        // Composer focus can change without immediate preedit/candidate updates.
        applyFunctionKitTitleOverride()
    }

    private fun applyFunctionKitTitleOverride() {
        val attachedWindow = activeExtendedWindow
        if (!functionKitEmbeddedComposerActive || attachedWindow !is FunctionKitWindow) {
            return
        }

        // While any ExtendedInputWindow is attached, the bar state machine stays in Title state
        // and never transitions back to Candidate on candidate/preedit updates. When the Function
        // Kit embedded composer is active, we must show candidates/preedit so users can commit
        // Chinese input inside Function Kit input fields.
        val shouldShowCandidates = !isPreeditEmpty || !isCandidateEmpty
        val desiredState =
            if (shouldShowCandidates) {
                KawaiiBarStateMachine.State.Candidate
            } else {
                KawaiiBarStateMachine.State.Title
            }
        if (barStateMachine.currentState != desiredState) {
            barStateMachine.unsafeJump(desiredState)
        }
    }

    val expandButtonStateMachine = ExpandButtonStateMachine.new {
        when (it) {
            ClickToAttachWindow -> {
                setExpandButtonToAttach()
                setExpandButtonEnabled(true)
            }
            ClickToDetachWindow -> {
                setExpandButtonToDetach()
                setExpandButtonEnabled(true)
            }
            Hidden -> {
                setExpandButtonEnabled(false)
            }
        }
    }

    // set expand candidate button to create expand candidate
    private fun setExpandButtonToAttach() {
        candidateUi.expandButton.setOnClickListener {
            windowManager.attachWindow(
                when (expandedCandidateStyle) {
                    ExpandedCandidateStyle.Grid -> GridExpandedCandidateWindow()
                    ExpandedCandidateStyle.Flexbox -> FlexboxExpandedCandidateWindow()
                }
            )
        }
        candidateUi.expandButton.setIcon(R.drawable.ic_baseline_expand_more_24)
        candidateUi.expandButton.contentDescription = context.getString(R.string.expand_candidates_list)
    }

    // set expand candidate button to close expand candidate
    private fun setExpandButtonToDetach() {
        candidateUi.expandButton.setOnClickListener {
            windowManager.attachWindow(KeyboardWindow)
        }
        candidateUi.expandButton.setIcon(R.drawable.ic_baseline_expand_less_24)
        candidateUi.expandButton.contentDescription = context.getString(R.string.hide_candidates_list)
    }

    // should be used with setExpandButtonToAttach or setExpandButtonToDetach
    private fun setExpandButtonEnabled(enabled: Boolean) {
        val shouldEnable = enabled && !functionKitCandidateDockActive
        candidateUi.expandButton.visibility = if (shouldEnable) View.VISIBLE else View.INVISIBLE
        candidateUi.expandButton.isEnabled = shouldEnable
    }

    private fun switchUiByState(state: KawaiiBarStateMachine.State) {
        val index = state.ordinal
        if (view.displayedChild == index) return
        val new = view.getChildAt(index)
        if (new != titleUi.root && activeExtendedWindow == null) {
            titleUi.setReturnButtonOnClickListener { }
            titleUi.setTitle("")
            titleUi.removeExtension()
        }
        view.displayedChild = index
    }

    override val view by lazy {
        ViewAnimator(context).apply {
            backgroundColor =
                if (ThemeManager.prefs.keyBorder.getValue()) Color.TRANSPARENT
                else theme.barColor
            add(idleUi.root, lParams(matchParent, matchParent))
            add(candidateSlot, lParams(matchParent, matchParent))
            add(titleUi.root, lParams(matchParent, matchParent))
        }
    }

    override fun onScopeSetupFinished(scope: DynamicScope) {
        ClipboardManager.lastEntry?.let {
            val now = System.currentTimeMillis()
            val clipboardTimeout = clipboardItemTimeout.getValue() * 1000L
            if (now - it.timestamp < clipboardTimeout) {
                onClipboardUpdateListener.onUpdate(it)
            }
        }
        ClipboardManager.addOnUpdateListener(onClipboardUpdateListener)
        clipboardSuggestion.registerOnChangeListener(onClipboardSuggestionUpdateListener)
        clipboardItemTimeout.registerOnChangeListener(onClipboardTimeoutUpdateListener)
        functionKitToolbarButton.registerOnChangeListener(onFunctionKitToolbarButtonChangeListener)

        if (removeFunctionKitKitSettingsListener == null) {
            removeFunctionKitKitSettingsListener =
                FunctionKitKitSettings.addOnChangeListener { key ->
                    val trimmed = key?.trim().orEmpty()
                    if (
                        trimmed == FunctionKitKitSettings.RegistryRevisionPreferenceKey ||
                        trimmed.endsWith(".enabled") ||
                        trimmed.endsWith(".pinned")
                    ) {
                        requestRefreshFunctionKitToolbarButtons()
                    }
                }
        }

        refreshFunctionKitToolbarButtons()

        if (removeTaskCenterListener == null) {
            removeTaskCenterListener =
                FunctionKitTaskHub.addListener {
                    windowManager.view.post {
                        updateTaskCenterBadge()
                    }
                }
        }
        updateTaskCenterBadge()
    }

    private fun updateTaskCenterBadge() {
        idleUi.buttonsUi.taskCenterButton.setBadgeVisible(
            FunctionKitTaskHub.snapshot().running.isNotEmpty()
        )
    }

    override fun onStartInput(info: EditorInfo, capFlags: CapabilityFlags) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            idleUi.privateMode(info.imeOptions.hasFlag(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING))
        }
        isCapabilityFlagsPassword = toolbarNumRowOnPassword && capFlags.has(CapabilityFlag.Password)
        isInlineSuggestionPresent = false
        numberRowState = NumberRowState.Auto
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            idleUi.inlineSuggestionsBar.clear()
        }
        voiceInputSubtype = InputMethodUtil.findVoiceSubtype(preferredVoiceInput)
        val shouldShowVoiceInput =
            showVoiceInputButton && voiceInputSubtype != null && !capFlags.has(CapabilityFlag.Password)
        idleUi.setHideKeyboardIsVoiceInput(
            shouldShowVoiceInput,
            if (shouldShowVoiceInput) switchToVoiceInputCallback else hideKeyboardCallback
        )
        evalIdleUiState()
    }

    override fun onPreeditEmptyStateUpdate(empty: Boolean) {
        isPreeditEmpty = empty
        barStateMachine.push(PreeditUpdated, PreeditEmpty to empty)
        applyFunctionKitTitleOverride()
    }

    override fun onCandidateUpdate(data: CandidateListEvent.Data) {
        isCandidateEmpty = data.candidates.isEmpty()
        barStateMachine.push(CandidatesUpdated, CandidateEmpty to isCandidateEmpty)
        applyFunctionKitTitleOverride()
    }

    override fun onWindowAttached(window: InputWindow) {
        when (window) {
            is InputWindow.ExtendedInputWindow<*> -> {
                activeExtendedWindow = window
                titleUi.setShowTitle(window.showTitle)
                titleUi.setTitle(window.title)
                window.onCreateBarExtension()?.let { titleUi.addExtension(it, window.showTitle) }
                titleUi.setReturnButtonOnClickListener {
                    windowManager.attachWindow(KeyboardWindow)
                }
                barStateMachine.push(ExtendedWindowAttached)
                applyFunctionKitTitleOverride()
            }
            else -> {}
        }
    }

    override fun onWindowDetached(window: InputWindow) {
        if (window is InputWindow.ExtendedInputWindow<*>) {
            if (activeExtendedWindow === window) {
                activeExtendedWindow = null
                functionKitEmbeddedComposerActive = false
            }
            // The title extension belongs to the detached window; clear it even if we are in
            // Candidate state due to Function Kit overrides.
            titleUi.setReturnButtonOnClickListener { }
            titleUi.setTitle("")
            titleUi.removeExtension()
        }
        barStateMachine.push(WindowDetached)
    }

    private val suggestionSize by lazy {
        Size(ViewGroup.LayoutParams.WRAP_CONTENT, context.dp(HEIGHT))
    }

    private val directExecutor by lazy {
        Executor { it.run() }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    fun handleInlineSuggestions(response: InlineSuggestionsResponse): Boolean {
        val suggestions = response.inlineSuggestions
        if (suggestions.isEmpty()) {
            isInlineSuggestionPresent = false
            evalIdleUiState()
            idleUi.inlineSuggestionsBar.clear()
            return true
        }
        var pinned: InlineSuggestion? = null
        val scrollable = mutableListOf<InlineSuggestion>()
        var extraPinnedCount = 0
        suggestions.forEach {
            if (it.info.isPinned) {
                if (pinned == null) {
                    pinned = it
                } else {
                    scrollable.add(extraPinnedCount++, it)
                }
            } else {
                scrollable.add(it)
            }
        }
        service.lifecycleScope.launch {
            idleUi.inlineSuggestionsBar.setPinnedView(
                pinned?.let { inflateInlineContentView(it) }
            )
        }
        service.lifecycleScope.launch {
            val views = scrollable.map { s ->
                service.lifecycleScope.async {
                    inflateInlineContentView(s)
                }
            }.awaitAll()
            idleUi.inlineSuggestionsBar.setScrollableViews(views)
        }
        isInlineSuggestionPresent = true
        evalIdleUiState()
        return true
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun inflateInlineContentView(suggestion: InlineSuggestion): InlineContentView? {
        return suspendCoroutine { c ->
            // callback view might be null
            suggestion.inflate(context, suggestionSize, directExecutor) { v ->
                c.resume(v)
            }
        }
    }

    companion object {
        const val HEIGHT = 40
    }

    fun onKeyboardLayoutSwitched(isNumber: Boolean) {
        isKeyboardLayoutNumber = isNumber
        evalIdleUiState()
    }

}

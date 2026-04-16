package org.fcitx.fcitx5.android.input.voice

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceEnum
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcastReceiver
import org.fcitx.fcitx5.android.input.dependency.UniqueViewComponent
import org.fcitx.fcitx5.android.input.dependency.context
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.mechdancer.dependency.Dependent
import org.mechdancer.dependency.UniqueComponent
import org.mechdancer.dependency.manager.ManagedHandler
import org.mechdancer.dependency.manager.managedHandler

enum class BuiltInVoiceEngine(
    override val stringRes: Int
) : ManagedPreferenceEnum {
    SherpaOnnx(R.string.voice_engine_sherpa_onnx),
    WhisperCpp(R.string.voice_engine_whisper_cpp)
}

enum class VoiceInputMode(
    override val stringRes: Int
) : ManagedPreferenceEnum {
    BuiltInSpeechRecognizer(R.string.voice_input_mode_builtin),
    SystemVoiceIme(R.string.voice_input_mode_system_ime)
}

enum class SherpaOnnxModelPreference(
    override val stringRes: Int
) : ManagedPreferenceEnum {
    Auto(R.string.voice_sherpa_model_preference_auto),
    MixedZhEn(R.string.voice_sherpa_model_preference_mixed_zh_en),
    HotwordEnhanced(R.string.voice_sherpa_model_preference_hotword),
    // Retained only so legacy preference values can be decoded and migrated safely.
    FastCtc(R.string.voice_sherpa_model_preference_fast_ctc)
}

enum class VoiceListeningState {
    NOT_TALKED_YET,
    MIC_MAY_BE_BLOCKED,
    TALKING,
    ENDING_SOON_VAD
}

internal data class VoiceInputEntryConfig(
    val showVoiceInputButton: Boolean,
    val spaceLongPressBehavior: org.fcitx.fcitx5.android.input.keyboard.SpaceLongPressBehavior,
    val voiceInputMode: VoiceInputMode
) {
    fun hasEnabledEntry(): Boolean = false

    fun shouldPrewarmBuiltInEngine(): Boolean = false
}

sealed class VoiceInputUiState(
    val phase: Phase
) {
    data object Idle : VoiceInputUiState(Phase.Idle)

    data class Listening(
        val transcript: String = "",
        val listeningState: VoiceListeningState = VoiceListeningState.NOT_TALKED_YET
    ) : VoiceInputUiState(Phase.Listening)

    data class Processing(
        val transcript: String = ""
    ) : VoiceInputUiState(Phase.Processing)

    val isIdle: Boolean
        get() = phase == Phase.Idle

    enum class Phase {
        Idle,
        Listening,
        Processing
    }
}

internal object VoiceInputLauncher {
    fun currentConfig(prefs: AppPrefs.Keyboard = AppPrefs.getInstance().keyboard): VoiceInputEntryConfig =
        VoiceInputEntryConfig(
            showVoiceInputButton = false,
            spaceLongPressBehavior = prefs.spaceKeyLongPressBehavior.getValue(),
            voiceInputMode = prefs.voiceInputMode.getValue()
        )

    fun shouldPrewarm(): Boolean = false

    fun isPreferredVoiceInputAvailable(prefs: AppPrefs.Keyboard = AppPrefs.getInstance().keyboard): Boolean = false

    fun isToolbarVoiceInputAvailable(
        service: FcitxInputMethodService,
        prefs: AppPrefs.Keyboard = AppPrefs.getInstance().keyboard
    ): Boolean = false

    fun launchPreferredVoiceInput(
        service: FcitxInputMethodService,
        windowManager: InputWindowManager,
        startListeningImmediately: Boolean = false,
        prefs: AppPrefs.Keyboard = AppPrefs.getInstance().keyboard
    ): Boolean = false
}

internal object VoiceInputPrewarmer {
    fun maybePrewarm(context: Context) {}
}

internal class VoiceHoldBubbleComponent :
    UniqueViewComponent<VoiceHoldBubbleComponent, FrameLayout>(),
    InputBroadcastReceiver {

    private val context by manager.context()

    override val view: FrameLayout by lazy {
        FrameLayout(context).apply {
            visibility = View.GONE
        }
    }
}

internal class VoiceCorrectionLearningController :
    UniqueComponent<VoiceCorrectionLearningController>(),
    Dependent,
    ManagedHandler by managedHandler(),
    InputBroadcastReceiver {

    fun beginObservation(
        sessionId: String,
        locale: String,
        packageName: String,
        committedText: String
    ) {}

    fun shutdown() {}
}

internal class VoiceInlineSessionController :
    UniqueComponent<VoiceInlineSessionController>(),
    Dependent,
    ManagedHandler by managedHandler(),
    InputBroadcastReceiver {

    fun startHoldToTalk(): Boolean = false

    fun stopHoldToTalk() {}

    fun shutdown() {}
}

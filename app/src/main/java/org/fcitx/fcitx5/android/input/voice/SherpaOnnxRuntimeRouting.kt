package org.fcitx.fcitx5.android.input.voice

import android.app.ActivityManager
import android.content.Context
import org.fcitx.fcitx5.android.voice.core.VoiceSessionRequest

internal data class SherpaRoutingDecision(
    val configuredPreference: SherpaOnnxModelPreference,
    val effectivePreference: SherpaOnnxModelPreference,
    val reasons: List<String>,
    val deviceProfile: SherpaOnnxRuntimeRouting.DeviceProfile
) {
    val summary: String
        get() = reasons.joinToString(separator = ", ")
}

internal object SherpaOnnxRuntimeRouting {
    data class DeviceProfile(
        val constrainedDevice: Boolean,
        val lowRamDevice: Boolean,
        val cpuCount: Int,
        val memoryClassMb: Int,
        val totalMemoryMb: Long
    )

    private data class SessionProfile(
        val locale: String,
        val hotwordCount: Int,
        val latinHotwordCount: Int,
        val hanHotwordCount: Int,
        val hasLatinContext: Boolean,
        val hasHanSelection: Boolean,
        val prefersMixedLanguage: Boolean,
        val prefersChineseHotwordBias: Boolean
    )

    fun decide(
        context: Context,
        configuredPreference: SherpaOnnxModelPreference,
        request: VoiceSessionRequest?
    ): SherpaRoutingDecision =
        decide(
            configuredPreference = configuredPreference,
            request = request,
            deviceProfile = detectDeviceProfile(context)
        )

    internal fun decide(
        configuredPreference: SherpaOnnxModelPreference,
        request: VoiceSessionRequest?,
        deviceProfile: DeviceProfile
    ): SherpaRoutingDecision {
        if (configuredPreference != SherpaOnnxModelPreference.Auto) {
            return SherpaRoutingDecision(
                configuredPreference = configuredPreference,
                effectivePreference = configuredPreference,
                reasons = listOf("explicit profile"),
                deviceProfile = deviceProfile
            )
        }

        val session = profileSession(request)
        val reasons = mutableListOf<String>()
        reasons += "auto profile"

        val effectivePreference =
            when {
                session.prefersMixedLanguage -> {
                    reasons += "mixed-language context"
                    SherpaOnnxModelPreference.MixedZhEn
                }
                session.prefersChineseHotwordBias -> {
                    reasons += "strong Chinese hotword context"
                    SherpaOnnxModelPreference.HotwordEnhanced
                }
                deviceProfile.constrainedDevice -> {
                    reasons += "latency-first constrained device"
                    SherpaOnnxModelPreference.FastCtc
                }
                else -> {
                    reasons += "balanced default bilingual route"
                    SherpaOnnxModelPreference.MixedZhEn
                }
            }

        return SherpaRoutingDecision(
            configuredPreference = configuredPreference,
            effectivePreference = effectivePreference,
            reasons = reasons,
            deviceProfile = deviceProfile
        )
    }

    fun detectDeviceProfile(context: Context): DeviceProfile {
        val activityManager =
            context.applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager?.getMemoryInfo(memoryInfo)
        val totalMemoryMb = memoryInfo.totalMem / (1024L * 1024L)
        val memoryClassMb = activityManager?.memoryClass ?: 0
        val cpuCount = Runtime.getRuntime().availableProcessors()
        val lowRamDevice = activityManager?.isLowRamDevice ?: false
        val constrainedDevice =
            lowRamDevice ||
                cpuCount <= 4 ||
                (memoryClassMb in 1..192) ||
                (totalMemoryMb in 1..4_096)

        return DeviceProfile(
            constrainedDevice = constrainedDevice,
            lowRamDevice = lowRamDevice,
            cpuCount = cpuCount,
            memoryClassMb = memoryClassMb,
            totalMemoryMb = totalMemoryMb
        )
    }

    private fun profileSession(request: VoiceSessionRequest?): SessionProfile {
        val hotwords = request?.hotwords.orEmpty()
        val locale = request?.locale.orEmpty()
        val selectedText = request?.selectedText.orEmpty()
        val leftContext = request?.leftContext.orEmpty().takeLast(96)

        val latinHotwordCount = hotwords.count(::containsLatinSignal)
        val hanHotwordCount = hotwords.count(::containsHanSignal)
        val hasLatinContext =
            containsLatinSignal(selectedText) ||
                containsLatinSignal(leftContext) ||
                latinHotwordCount > 0
        val hasHanSelection = containsHanSignal(selectedText)
        val prefersMixedLanguage =
            locale.startsWith("en", ignoreCase = true) ||
                locale.contains("latn", ignoreCase = true) ||
                hasLatinContext
        val prefersChineseHotwordBias =
            !prefersMixedLanguage &&
                (
                    hanHotwordCount >= 2 ||
                        (hotwords.size >= 3 && latinHotwordCount == 0) ||
                        hasHanSelection
                )

        return SessionProfile(
            locale = locale,
            hotwordCount = hotwords.size,
            latinHotwordCount = latinHotwordCount,
            hanHotwordCount = hanHotwordCount,
            hasLatinContext = hasLatinContext,
            hasHanSelection = hasHanSelection,
            prefersMixedLanguage = prefersMixedLanguage,
            prefersChineseHotwordBias = prefersChineseHotwordBias
        )
    }

    private fun containsLatinSignal(value: String): Boolean =
        latinSignalRegex.containsMatchIn(value)

    private fun containsHanSignal(value: String): Boolean =
        value.count(::isHanCharacter) >= 2

    private fun isHanCharacter(character: Char): Boolean =
        Character.UnicodeScript.of(character.code) == Character.UnicodeScript.HAN

    private val latinSignalRegex = Regex("\\b[A-Za-z][A-Za-z0-9._+\\-]{1,}\\b")
}

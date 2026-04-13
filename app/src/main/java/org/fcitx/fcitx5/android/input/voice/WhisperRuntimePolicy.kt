package org.fcitx.fcitx5.android.input.voice

import android.os.Build
import java.util.Locale

internal enum class WhisperBackend {
    Gpu,
    Cpu
}

internal data class WhisperRuntimeProfile(
    val backend: WhisperBackend,
    val effectivePreference: VoiceModelPreference,
    val reason: String?
)

internal object WhisperRuntimePolicy {
    private val gpuBlacklistedDevices =
        setOf(
            DeviceKey(manufacturer = "vivo", model = "V2244A")
        )

    fun resolve(
        configuredPreference: VoiceModelPreference,
        forceCpuFallback: Boolean,
        persistedGpuDisabled: Boolean,
        manufacturer: String = Build.MANUFACTURER,
        model: String = Build.MODEL
    ): WhisperRuntimeProfile {
        val deviceKey = DeviceKey.from(manufacturer, model)
        val reason =
            when {
                forceCpuFallback -> "runtime-gpu-failure"
                persistedGpuDisabled -> "persisted-gpu-disable"
                deviceKey in gpuBlacklistedDevices ->
                    "device-gpu-blacklist:${deviceKey.manufacturer}/${deviceKey.model}"
                else -> null
            }
        val backend = if (reason == null) WhisperBackend.Gpu else WhisperBackend.Cpu
        val effectivePreference =
            when {
                backend == WhisperBackend.Cpu &&
                    configuredPreference in setOf(
                        VoiceModelPreference.Auto,
                        VoiceModelPreference.Balanced
                    ) -> VoiceModelPreference.Fast
                else -> configuredPreference
            }
        return WhisperRuntimeProfile(
            backend = backend,
            effectivePreference = effectivePreference,
            reason = reason
        )
    }

    private data class DeviceKey(
        val manufacturer: String,
        val model: String
    ) {
        companion object {
            fun from(
                manufacturer: String,
                model: String
            ): DeviceKey =
                DeviceKey(
                    manufacturer = manufacturer.trim().lowercase(Locale.ROOT),
                    model = model.trim().uppercase(Locale.ROOT)
                )
        }
    }
}

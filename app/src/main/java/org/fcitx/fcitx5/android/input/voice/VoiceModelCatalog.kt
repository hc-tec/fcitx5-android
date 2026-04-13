package org.fcitx.fcitx5.android.input.voice

internal data class VoiceModelDescriptor(
    val engineId: String,
    val modelId: String,
    val assetPath: String,
    val tier: String,
    val quantized: Boolean
)

internal object VoiceModelCatalog {
    private const val WHISPER_ENGINE_ID = "whisper.cpp"
    private const val MODEL_ASSET_DIR = "models"

    fun selectWhisperModel(
        assetNames: Array<String>,
        preference: VoiceModelPreference = VoiceModelPreference.Balanced,
        preferredModelId: String? = null
    ): VoiceModelDescriptor? {
        val descriptors =
            assetNames
                .mapNotNull(::descriptorFromWhisperAsset)
                .sortedBy { it.modelId }

        val preferredOrder =
            buildList {
                preferredModelId?.trim()?.takeIf { it.isNotBlank() }?.let(::add)
                addAll(preferredWhisperModelIds(preference))
            }

        preferredOrder.forEach { candidate ->
            descriptors.firstOrNull { it.modelId == candidate }?.let { return it }
        }

        return descriptors.firstOrNull()
    }

    fun preferredWhisperModelIds(preference: VoiceModelPreference): List<String> =
        when (preference) {
            VoiceModelPreference.Auto ->
                listOf(
                    "base-q5_1",
                    "tiny-q5_1",
                    "base-q8_0",
                    "tiny-q8_0",
                    "base",
                    "small-q5_1",
                    "small",
                    "tiny"
                )
            VoiceModelPreference.Fast ->
                listOf(
                    "tiny-q5_1",
                    "tiny-q8_0",
                    "tiny",
                    "base-q5_1",
                    "base-q8_0",
                    "base",
                    "small-q5_1",
                    "small"
                )
            VoiceModelPreference.Balanced ->
                listOf(
                    "base-q5_1",
                    "base-q8_0",
                    "base",
                    "tiny-q5_1",
                    "small-q5_1",
                    "small",
                    "tiny"
                )
            VoiceModelPreference.Accurate ->
                listOf(
                    "small-q5_1",
                    "small-q8_0",
                    "small",
                    "base-q5_1",
                    "base-q8_0",
                    "base",
                    "tiny-q5_1",
                    "tiny"
                )
        }

    fun descriptorFromWhisperAsset(assetName: String): VoiceModelDescriptor? {
        val trimmed = assetName.trim()
        if (!trimmed.startsWith("ggml-") || !trimmed.endsWith(".bin")) {
            return null
        }

        val modelId = trimmed.removePrefix("ggml-").removeSuffix(".bin")
        val tier =
            when {
                modelId.startsWith("tiny") -> "tiny"
                modelId.startsWith("base") -> "base"
                modelId.startsWith("small") -> "small"
                modelId.startsWith("medium") -> "medium"
                modelId.startsWith("large") -> "large"
                else -> "other"
            }

        return VoiceModelDescriptor(
            engineId = WHISPER_ENGINE_ID,
            modelId = modelId,
            assetPath = "$MODEL_ASSET_DIR/$trimmed",
            tier = tier,
            quantized = modelId.contains("-q")
        )
    }
}

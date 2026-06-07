package helium314.keyboard.latin.voice

import kotlinx.serialization.Serializable

@Serializable
enum class ReformatTone(val displayKey: String) {
    NONE("voice_reformat_tone_none"),
    CASUAL("voice_reformat_tone_casual"),
    FORMAL("voice_reformat_tone_formal"),
    PROFESSIONAL("voice_reformat_tone_professional"),
    FRIENDLY("voice_reformat_tone_friendly"),
    CONCISE("voice_reformat_tone_concise"),
    EXPAND("voice_reformat_tone_expand");

    companion object {
        fun fromKey(key: String?): ReformatTone =
            entries.firstOrNull { it.name.equals(key, ignoreCase = true) } ?: CASUAL
    }
}

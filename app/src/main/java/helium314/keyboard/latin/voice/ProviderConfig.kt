package helium314.keyboard.latin.voice

import kotlinx.serialization.Serializable

@Serializable
data class ProviderConfig(
    val id: String,
    val name: String,
    val kind: ProviderKind,
    val baseUrl: String,
    val apiKey: String,
    val models: List<SttModel> = emptyList(),
    val defaultModelId: String? = null,
    val lastFetched: Long = 0L,
    val enabled: Boolean = true,
    val isBuiltin: Boolean = false,
)

@Serializable
enum class ProviderKind { OPENAI_COMPATIBLE, GEMINI, CUSTOM }

@Serializable
data class SttModel(
    val id: String,
    val displayName: String,
    val ownedBy: String = "",
)

@Serializable
data class ProviderTemplate(
    val name: String,
    val kind: ProviderKind,
    val baseUrl: String,
    val defaultModels: List<String>,
    val defaultLanguage: String? = null,
)

object BuiltinProviders {
    val ALL: List<ProviderTemplate> = listOf(
        ProviderTemplate("Groq", ProviderKind.OPENAI_COMPATIBLE,
            "https://api.groq.com/openai/v1",
            listOf("whisper-large-v3-turbo", "whisper-large-v3", "distil-whisper-large-v3-en")),
        ProviderTemplate("OpenAI", ProviderKind.OPENAI_COMPATIBLE,
            "https://api.openai.com/v1",
            listOf("whisper-1", "gpt-4o-transcribe", "gpt-4o-mini-transcribe")),
        ProviderTemplate("Mistral", ProviderKind.OPENAI_COMPATIBLE,
            "https://api.mistral.ai/v1",
            listOf("voxtral-mini-latest", "voxtral-small-latest")),
        ProviderTemplate("Deepgram", ProviderKind.OPENAI_COMPATIBLE,
            "https://api.deepgram.com/v1",
            listOf("nova-3", "nova-2", "whisper-large")),
        ProviderTemplate("Fireworks", ProviderKind.OPENAI_COMPATIBLE,
            "https://api.fireworks.ai/inference/v1",
            listOf("whisper-v3-turbo", "whisper-v3")),
        ProviderTemplate("Together", ProviderKind.OPENAI_COMPATIBLE,
            "https://api.together.xyz/v1",
            listOf("whisper-large-v3")),
        ProviderTemplate("OpenRouter", ProviderKind.OPENAI_COMPATIBLE,
            "https://openrouter.ai/api/v1",
            listOf("google/gemini-2.0-flash-001", "openai/whisper-large-v3")),
        ProviderTemplate("Google Gemini", ProviderKind.GEMINI,
            "https://generativelanguage.googleapis.com/v1beta",
            listOf("gemini-2.0-flash", "gemini-2.5-flash", "gemini-2.5-pro")),
    )

    fun builtinConfigs(): List<ProviderConfig> = ALL.map { tmpl ->
        ProviderConfig(
            id = "builtin-${tmpl.name.lowercase().replace(" ", "-")}",
            name = tmpl.name,
            kind = tmpl.kind,
            baseUrl = tmpl.baseUrl,
            apiKey = "",
            models = tmpl.defaultModels.map { SttModel(it, it) },
            defaultModelId = tmpl.defaultModels.firstOrNull(),
            isBuiltin = true,
        )
    }
}

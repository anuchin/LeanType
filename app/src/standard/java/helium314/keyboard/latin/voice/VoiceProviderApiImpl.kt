package helium314.keyboard.latin.voice

object VoiceProviderApiImpl {
    private fun provider(config: ProviderConfig): SttProvider {
        return when (config.kind) {
            ProviderKind.GEMINI -> GeminiSttProvider(config)
            else -> OpenAICompatibleSttProvider(config)
        }
    }

    suspend fun testKey(config: ProviderConfig): Result<Unit> = provider(config).testKey()
    suspend fun fetchModels(config: ProviderConfig): Result<List<SttModel>> = provider(config).fetchModels()
    suspend fun transcribe(
        config: ProviderConfig,
        wav: ByteArray,
        modelId: String,
        language: String?,
    ): Result<String> = provider(config).transcribe(wav, modelId, language)
}

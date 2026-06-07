package helium314.keyboard.latin.voice

object VoiceProviderApi {
    suspend fun testKey(config: ProviderConfig): Result<Unit> = VoiceProviderApiImpl.testKey(config)
    suspend fun fetchModels(config: ProviderConfig): Result<List<SttModel>> = VoiceProviderApiImpl.fetchModels(config)
    suspend fun transcribe(
        config: ProviderConfig,
        wav: ByteArray,
        modelId: String,
        language: String?,
    ): Result<String> = VoiceProviderApiImpl.transcribe(config, wav, modelId, language)
}

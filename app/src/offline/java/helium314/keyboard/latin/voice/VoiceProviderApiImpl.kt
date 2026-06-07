package helium314.keyboard.latin.voice

object VoiceProviderApiImpl {
    suspend fun testKey(config: ProviderConfig): Result<Unit> =
        Result.failure(Exception("Voice transcription is not available in the offline build."))
    suspend fun fetchModels(config: ProviderConfig): Result<List<SttModel>> =
        Result.failure(Exception("Voice transcription is not available in the offline build."))
    suspend fun transcribe(
        config: ProviderConfig,
        wav: ByteArray,
        modelId: String,
        language: String?,
    ): Result<String> =
        Result.failure(Exception("Voice transcription is not available in the offline build."))
}

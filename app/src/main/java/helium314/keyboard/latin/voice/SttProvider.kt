package helium314.keyboard.latin.voice

interface SttProvider {
    suspend fun testKey(): Result<Unit>
    suspend fun fetchModels(): Result<List<SttModel>>
    suspend fun transcribe(
        wav: ByteArray,
        modelId: String,
        language: String?,
    ): Result<String>
}

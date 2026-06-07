package helium314.keyboard.latin.voice

import android.content.Context
import helium314.keyboard.latin.utils.ProofreadService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object VoiceReformatterImpl {
    suspend fun reformat(context: Context, text: String, tone: ReformatTone): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                ProofreadService(context).reformat(text, tone)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Result.failure(e)
            }
        }
}

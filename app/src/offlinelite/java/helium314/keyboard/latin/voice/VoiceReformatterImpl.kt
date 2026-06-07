package helium314.keyboard.latin.voice

import android.content.Context

object VoiceReformatterImpl {
    suspend fun reformat(context: Context, text: String, tone: ReformatTone): Result<String> =
        Result.failure(Exception("Reformatting is not available in this build."))
}

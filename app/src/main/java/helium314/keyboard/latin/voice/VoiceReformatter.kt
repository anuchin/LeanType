package helium314.keyboard.latin.voice

import android.content.Context
import kotlinx.coroutines.runBlocking

object VoiceReformatter {
    fun reformat(context: Context, text: String, tone: ReformatTone): Result<String> {
        if (tone == ReformatTone.NONE) return Result.success(text)
        return runBlocking { VoiceReformatterImpl.reformat(context, text, tone) }
    }
}

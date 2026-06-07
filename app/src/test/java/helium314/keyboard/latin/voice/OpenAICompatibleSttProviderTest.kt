// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAICompatibleSttProviderTest {

    private val sampleWav: ByteArray = WavEncoder.encode(ByteArray(64) { it.toByte() })

    @Test
    fun buildMultipartBody_containsModelAndFile() {
        val boundary = "TEST-BOUNDARY"
        val body = OpenAICompatibleSttProvider.buildMultipartBody(
            boundary = boundary,
            modelId = "whisper-1",
            language = null,
            wav = sampleWav,
        )
        val text = String(body, Charsets.UTF_8)
        assertTrue(text.contains("Content-Disposition: form-data; name=\"model\""))
        assertTrue(text.contains("whisper-1"))
        assertTrue(text.contains("Content-Disposition: form-data; name=\"file\"; filename=\"audio.wav\""))
        assertTrue(text.contains("Content-Type: audio/wav"))
        assertTrue(text.contains("--$boundary--"))
    }

    @Test
    fun buildMultipartBody_containsLanguageWhenProvided() {
        val body = OpenAICompatibleSttProvider.buildMultipartBody(
            boundary = "BND",
            modelId = "m",
            language = "es",
            wav = sampleWav,
        )
        val text = String(body, Charsets.UTF_8)
        assertTrue(text.contains("Content-Disposition: form-data; name=\"language\""))
        assertTrue(text.contains("es"))
    }

    @Test
    fun buildMultipartBody_omitsLanguageWhenNull() {
        val body = OpenAICompatibleSttProvider.buildMultipartBody(
            boundary = "BND",
            modelId = "m",
            language = null,
            wav = sampleWav,
        )
        val text = String(body, Charsets.UTF_8)
        assertFalse(text.contains("name=\"language\""))
    }

    @Test
    fun buildMultipartBody_omitsLanguageWhenBlank() {
        val body = OpenAICompatibleSttProvider.buildMultipartBody(
            boundary = "BND",
            modelId = "m",
            language = "   ",
            wav = sampleWav,
        )
        val text = String(body, Charsets.UTF_8)
        assertFalse(text.contains("name=\"language\""))
    }

    @Test
    fun buildMultipartBody_includesEntireWavPayload() {
        val wav = ByteArray(1024) { (it and 0xFF).toByte() }
        val body = OpenAICompatibleSttProvider.buildMultipartBody(
            boundary = "BND",
            modelId = "m",
            language = null,
            wav = wav,
        )
        val wavIndex = body.searchBytes(wav)
        assertTrue("expected wav payload to appear in multipart body", wavIndex >= 0)
    }

    private fun ByteArray.searchBytes(needle: ByteArray): Int {
        if (needle.isEmpty()) return 0
        outer@ for (i in 0..(this.size - needle.size)) {
            for (j in needle.indices) {
                if (this[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }
}

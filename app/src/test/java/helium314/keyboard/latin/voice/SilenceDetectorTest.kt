// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SilenceDetectorTest {

    @Test
    fun loudAudio_isNotSilent() {
        val detector = SilenceDetector()
        repeat(50) { detector.feed(0.5f) }
        assertFalse(detector.isSilent)
        assertEquals(0L, detector.silenceDurationMs)
    }

    @Test
    fun quietAudio_accumulatesSilenceDuration() {
        val detector = SilenceDetector(windowMs = 200L, threshold = 0.01f)
        repeat(5) { detector.feed(0.001f) }
        assertTrue(detector.silenceDurationMs > 0L)
    }

    @Test
    fun quietAudioLongEnough_reportsIsSilent() {
        val detector = SilenceDetector(windowMs = 200L, threshold = 0.01f)
        repeat(50) { detector.feed(0.0001f) }
        assertTrue(detector.isSilent)
    }

    @Test
    fun loudBurstResetsSilenceCounter() {
        val detector = SilenceDetector(windowMs = 200L, threshold = 0.01f)
        repeat(20) { detector.feed(0.0001f) }
        val accumulated = detector.silenceDurationMs
        assertTrue(accumulated > 0L)
        detector.feed(0.5f)
        assertEquals(0L, detector.silenceDurationMs)
        assertFalse(detector.isSilent)
    }

    @Test
    fun reset_clearsState() {
        val detector = SilenceDetector()
        repeat(50) { detector.feed(0.0001f) }
        detector.reset()
        assertEquals(0L, detector.silenceDurationMs)
        assertFalse(detector.isSilent)
    }

    @Test
    fun feedSamples_zeroAmplitudePcmIsSilent() {
        val detector = SilenceDetector()
        val silence = ByteArray(1024)
        detector.feedSamples(silence)
        assertTrue(detector.isSilent)
    }

    @Test
    fun feedSamples_loudPcmIsNotSilent() {
        val detector = SilenceDetector(threshold = 0.001f)
        val pcm = ShortArray(512) { (it.toShort() * 100) }
        val bytes = ByteArray(pcm.size * 2)
        for (i in pcm.indices) {
            val v = pcm[i].toInt()
            bytes[i * 2] = (v and 0xFF).toByte()
            bytes[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        detector.feedSamples(bytes)
        assertFalse(detector.isSilent)
    }

    @Test
    fun feedSamples_oddByteLength_ignoresTrailingByte() {
        val detector = SilenceDetector()
        val bytes = byteArrayOf(0x00, 0x10, 0x00)
        detector.feedSamples(bytes)
        assertEquals(0L, detector.silenceDurationMs)
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class WavEncoderTest {

    @Test
    fun encode_emptyPcm_emitsValidHeaderAndZeroData() {
        val pcm = ByteArray(0)
        val wav = WavEncoder.encode(pcm, sampleRate = 16_000, channels = 1, bitsPerSample = 16)

        assertEquals(44, wav.size)
        assertEquals('R'.code.toByte(), wav[0])
        assertEquals('I'.code.toByte(), wav[1])
        assertEquals('F'.code.toByte(), wav[2])
        assertEquals('F'.code.toByte(), wav[3])
        assertEquals(36, readIntLE(wav, 4))
        assertEquals('W'.code.toByte(), wav[8])
        assertEquals('A'.code.toByte(), wav[9])
        assertEquals('V'.code.toByte(), wav[10])
        assertEquals('E'.code.toByte(), wav[11])
        assertEquals('f'.code.toByte(), wav[12])
        assertEquals('m'.code.toByte(), wav[13])
        assertEquals('t'.code.toByte(), wav[14])
        assertEquals(' '.code.toByte(), wav[15])
        assertEquals(16, readIntLE(wav, 16))
        assertEquals(1, readShortLE(wav, 20).toInt())
        assertEquals(1, readShortLE(wav, 22).toInt())
        assertEquals(16_000, readIntLE(wav, 24))
        assertEquals(32_000, readIntLE(wav, 28))
        assertEquals(2, readShortLE(wav, 32).toInt())
        assertEquals(16, readShortLE(wav, 34).toInt())
        assertEquals('d'.code.toByte(), wav[36])
        assertEquals('a'.code.toByte(), wav[37])
        assertEquals('t'.code.toByte(), wav[38])
        assertEquals('a'.code.toByte(), wav[39])
        assertEquals(0, readIntLE(wav, 40))
    }

    @Test
    fun encode_nonEmptyPcm_appendsPcmAfterHeader() {
        val pcm = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06)
        val wav = WavEncoder.encode(pcm)
        assertEquals(44 + pcm.size, wav.size)
        assertArrayEquals(pcm, wav.copyOfRange(44, wav.size))
        assertEquals(pcm.size, readIntLE(wav, 40))
    }

    @Test
    fun encode_dataSizeAndRiffSizeMatchPcm() {
        val pcm = ByteArray(2_000) { (it and 0xFF).toByte() }
        val wav = WavEncoder.encode(pcm, sampleRate = 8_000, channels = 1, bitsPerSample = 16)
        assertEquals(pcm.size, readIntLE(wav, 40))
        assertEquals(36 + pcm.size, readIntLE(wav, 4))
        assertEquals(8_000, readIntLE(wav, 24))
        assertEquals(16_000, readIntLE(wav, 28))
    }

    private fun readIntLE(b: ByteArray, offset: Int): Int =
        (b[offset].toInt() and 0xFF) or
        ((b[offset + 1].toInt() and 0xFF) shl 8) or
        ((b[offset + 2].toInt() and 0xFF) shl 16) or
        ((b[offset + 3].toInt() and 0xFF) shl 24)

    private fun readShortLE(b: ByteArray, offset: Int): Short =
        (((b[offset + 1].toInt() and 0xFF) shl 8) or (b[offset].toInt() and 0xFF)).toShort()
}

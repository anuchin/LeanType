package helium314.keyboard.latin.voice

import kotlin.math.sqrt

class SilenceDetector(
    private val threshold: Float = 0.0056f,
    private val windowMs: Long = 200L,
    private val sampleRate: Int = 16_000,
) {
    private val windowSamples: Int = (sampleRate * windowMs / 1000).toInt().coerceAtLeast(1)
    private val samples = ArrayDeque<Float>(windowSamples)
    private var silenceMs: Long = 0L
    private val sampleDurationMs: Float = 1000f / sampleRate

    fun reset() {
        samples.clear()
        silenceMs = 0L
    }

    fun feed(rms: Float): Boolean {
        samples.addLast(rms)
        if (samples.size > windowSamples) samples.removeFirst()
        val avg = if (samples.isEmpty()) 0f else samples.sum() / samples.size
        return if (avg < threshold) {
            silenceMs += (windowMs / samples.size.coerceAtLeast(1))
            true
        } else {
            silenceMs = 0L
            false
        }
    }

    fun feedSamples(pcm16le: ByteArray, offset: Int = 0, length: Int = pcm16le.size - offset) {
        if (length <= 0) return
        var sumSquares = 0.0
        var count = 0
        var i = offset
        val end = offset + length
        while (i + 1 < end) {
            val low = pcm16le[i].toInt() and 0xFF
            val high = pcm16le[i + 1].toInt()
            val sample = (high shl 8) or low
            val signed = sample.toShort().toInt()
            sumSquares += (signed * signed).toDouble()
            count++
            i += 2
        }
        if (count == 0) return
        val rms = sqrt(sumSquares / count).toFloat() / Short.MAX_VALUE.toFloat()
        feed(rms)
    }

    val silenceDurationMs: Long get() = silenceMs
    val isSilent: Boolean get() = silenceMs >= windowMs
}

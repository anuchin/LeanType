package helium314.keyboard.latin.voice

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val barCount = 64
    private val samples = FloatArray(barCount)
    private var writeIndex = 0
    private var filled = 0

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1A73E8")
        strokeWidth = 4f
        strokeCap = Paint.Cap.ROUND
    }

    private val idlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1A73E8")
        strokeWidth = 4f
        strokeCap = Paint.Cap.ROUND
        alpha = 60
    }

    private var isActive = false

    fun pushSample(rms: Float) {
        val clamped = rms.coerceIn(0f, 1f)
        samples[writeIndex] = clamped
        writeIndex = (writeIndex + 1) % barCount
        if (filled < barCount) filled++
        invalidate()
    }

    fun setActive(active: Boolean) {
        if (isActive != active) {
            isActive = active
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val gap = 2f
        val barWidth = max(2f, (w / barCount) - gap)
        val centerY = h / 2f
        val paint = if (isActive) barPaint else idlePaint

        for (i in 0 until barCount) {
            val idx = (writeIndex + i) % barCount
            val sample = if (filled == 0) 0.05f else samples[idx]
            val displayIdx = (i + (barCount - filled)) % barCount
            val actualSample = if (displayIdx >= barCount - filled) sample else 0.05f
            val barH = max(4f, actualSample * (h * 0.45f))
            val x = i * (barWidth + gap) + barWidth / 2f
            canvas.drawLine(x, centerY - barH, x, centerY + barH, paint)
        }

        if (isActive) {
            postInvalidateOnAnimation()
        }
    }

    fun clear() {
        for (i in samples.indices) samples[i] = 0.05f
        writeIndex = 0
        filled = 0
        invalidate()
    }
}

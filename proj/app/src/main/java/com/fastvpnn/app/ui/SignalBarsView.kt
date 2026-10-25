package com.fastvpnn.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.fastvpnn.app.R

/** Four ascending bars, 0..4 lit. Used for the ping quality of a server. */
class SignalBarsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var level: Int = 0
        set(value) {
            val v = value.coerceIn(0, BARS)
            if (field == v) return
            field = v
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val density = resources.displayMetrics.density

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            resolveSize((22 * density).toInt(), widthMeasureSpec),
            resolveSize((16 * density).toInt(), heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        val gap = 2f * density
        val barW = (width - gap * (BARS - 1)) / BARS
        val onColor = ContextCompat.getColor(
            context,
            when {
                level >= 3 -> R.color.statusOnline
                level == 2 -> R.color.statusWarn
                else -> R.color.statusOffline
            }
        )
        val offColor = ContextCompat.getColor(context, R.color.signalOff)
        for (i in 0 until BARS) {
            val h = height * (0.35f + 0.65f * (i / (BARS - 1f)))
            val left = i * (barW + gap)
            rect.set(left, height - h, left + barW, height.toFloat())
            paint.color = if (i < level) onColor else offColor
            canvas.drawRoundRect(rect, barW / 2f, barW / 2f, paint)
        }
    }

    companion object {
        private const val BARS = 4

        /** Every server that isn't confirmed offline (-2) shows full green bars, whatever its
         *  ping -- bars only go dark when the server can't be reached at all. */
        fun levelForPing(pingMs: Int): Int = if (pingMs == -2) 0 else BARS
    }
}

package com.fastvpnn.app.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import com.fastvpnn.app.R

/**
 * Background of the big round power button: soft glow, dark disc and a gradient ring.
 * Everything is drawn with Canvas primitives (no bitmaps). The power icon and the
 * status texts are ordinary child views layered on top of this view in the layout.
 */
class PowerRingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class State { DISCONNECTED, CONNECTING, CONNECTED }

    var ringState: State = State.DISCONNECTED
        set(value) {
            if (field == value) return
            field = value
            rebuildShaders()
            updateAnimator()
            invalidate()
        }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val discPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private var spin = 0f
    private var animator: ValueAnimator? = null
    private var cx = 0f
    private var cy = 0f
    private var ringRadius = 0f
    private var discRadius = 0f
    private var glowRadius = 0f

    private fun color(id: Int) = ContextCompat.getColor(context, id)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val size = minOf(w, h).toFloat()
        cx = w / 2f
        cy = h / 2f
        glowRadius = size / 2f
        ringPaint.strokeWidth = size * 0.055f
        ringRadius = size / 2f - size * 0.09f
        discRadius = ringRadius - ringPaint.strokeWidth / 2f
        rebuildShaders()
    }

    private fun rebuildShaders() {
        if (glowRadius <= 0f) return
        val glowColor = when (ringState) {
            State.CONNECTED -> 0x6600E5A8
            State.CONNECTING -> 0x552D8CFF
            State.DISCONNECTED -> 0x221E3A66
        }
        glowPaint.shader = RadialGradient(
            cx, cy, glowRadius,
            intArrayOf(glowColor, glowColor and 0x00FFFFFF),
            floatArrayOf(0.55f, 1f), Shader.TileMode.CLAMP
        )
        discPaint.shader = RadialGradient(
            cx, cy, discRadius,
            intArrayOf(0xFF0E2248.toInt(), 0xFF050F26.toInt()),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
        )
        val colors = when (ringState) {
            State.DISCONNECTED -> intArrayOf(0xFF2B3E63.toInt(), 0xFF3B5B8C.toInt(), 0xFF2B3E63.toInt())
            else -> intArrayOf(color(R.color.ringCyan), color(R.color.ringGreen), color(R.color.ringBlue), color(R.color.ringCyan))
        }
        ringPaint.shader = SweepGradient(cx, cy, colors, null)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawCircle(cx, cy, glowRadius, glowPaint)
        canvas.drawCircle(cx, cy, discRadius, discPaint)
        canvas.save()
        canvas.rotate(spin, cx, cy)
        canvas.drawCircle(cx, cy, ringRadius, ringPaint)
        canvas.restore()
    }

    private fun updateAnimator() {
        if (ringState == State.CONNECTING && isAttachedToWindow) {
            if (animator == null) {
                animator = ValueAnimator.ofFloat(0f, 360f).apply {
                    duration = 1400
                    repeatCount = ValueAnimator.INFINITE
                    interpolator = LinearInterpolator()
                    addUpdateListener { spin = it.animatedValue as Float; invalidate() }
                    start()
                }
            }
        } else {
            animator?.cancel()
            animator = null
            spin = 0f
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateAnimator()
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }
}

package com.hingevala

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The big round Start/Stop button: a glowing "core" with a HUD tick ring. Idle it glows
 * dim cyan; running, gradient arcs spin around it and it pulses.
 */
class CoreButton(ctx: Context) : View(ctx) {
    var running = false
        set(value) {
            if (field == value) return
            field = value
            if (value) spin.start() else spin.cancel()
            invalidate()
        }
    var label = "START"
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }
    var caption = "tap to run"
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    private var phase = 0f
    private var pressedScale = 1f
    private val spin = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2600
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            phase = it.animatedValue as Float
            invalidate()
        }
    }

    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ticks = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val arcs = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val disk = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Theme.text
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        letterSpacing = 0.18f
    }
    private val captionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Theme.muted
        textAlign = Paint.Align.CENTER
    }
    private val oval = RectF()

    init {
        isClickable = true
        isFocusable = true
        contentDescription = "Start or stop the bot"
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val side = min(w, (resources.displayMetrics.density * 260).toInt())
        setMeasuredDimension(w, side)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> animatePress(0.95f)
            MotionEvent.ACTION_UP -> {
                animatePress(1f)
                performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            }
            MotionEvent.ACTION_CANCEL -> animatePress(1f)
        }
        return super.onTouchEvent(event)
    }

    private fun animatePress(to: Float) {
        ValueAnimator.ofFloat(pressedScale, to).apply {
            duration = 120
            addUpdateListener {
                pressedScale = it.animatedValue as Float
                invalidate()
            }
        }.start()
    }

    override fun onDetachedFromWindow() {
        spin.cancel()
        super.onDetachedFromWindow()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (running) spin.start()
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f * 0.94f * pressedScale
        val accent = if (running) Theme.pink else Theme.cyan
        val pulse = if (running) (sin(phase * Math.PI * 4).toFloat() + 1f) / 2f else 0f

        // Soft outer glow.
        glow.shader = RadialGradient(
            cx, cy, r,
            intArrayOf(Theme.alpha(accent, (70 + 50 * pulse).toInt()), Theme.alpha(accent, 0)),
            floatArrayOf(0.55f, 1f), Shader.TileMode.CLAMP,
        )
        c.drawCircle(cx, cy, r, glow)

        // HUD tick ring; every fifth tick longer.
        val tickOuter = r * 0.9f
        for (i in 0 until 72) {
            val a = Math.toRadians(i * 5.0 - 90 + if (running) phase * 30 else 0f).toFloat()
            val long = i % 6 == 0
            val inner = tickOuter - r * (if (long) 0.07f else 0.035f)
            ticks.color = Theme.alpha(Theme.cyan, if (long) 150 else 70)
            ticks.strokeWidth = r * (if (long) 0.012f else 0.008f)
            c.drawLine(
                cx + cos(a) * inner, cy + sin(a) * inner,
                cx + cos(a) * tickOuter, cy + sin(a) * tickOuter, ticks,
            )
        }

        // Three arcs; spinning gradient while running, dim and still when idle.
        val arcR = r * 0.76f
        oval.set(cx - arcR, cy - arcR, cx + arcR, cy + arcR)
        arcs.strokeWidth = r * 0.055f
        if (running) {
            arcs.shader = SweepGradient(cx, cy, intArrayOf(Theme.pink, Theme.orange, Theme.pink), null)
            arcs.alpha = 255
        } else {
            arcs.shader = null
            arcs.color = Theme.alpha(Theme.cyan, 90)
        }
        val start = if (running) phase * 360f else -90f
        for (k in 0 until 3) c.drawArc(oval, start + k * 120f + 12f, 96f, false, arcs)

        // The core: a dark disk with a glowing rim.
        val coreR = r * 0.6f
        disk.shader = RadialGradient(
            cx, cy - coreR * 0.3f, coreR * 1.2f,
            intArrayOf(Theme.surface2, Theme.bg), null, Shader.TileMode.CLAMP,
        )
        c.drawCircle(cx, cy, coreR, disk)
        rim.strokeWidth = r * 0.018f
        rim.shader = SweepGradient(
            cx, cy,
            if (running) intArrayOf(Theme.pink, Theme.orange, Theme.pink) else intArrayOf(Theme.cyan, Theme.alpha(Theme.cyan, 60), Theme.cyan),
            null,
        )
        c.drawCircle(cx, cy, coreR + r * 0.02f * pulse, rim)

        labelPaint.textSize = r * 0.2f
        c.drawText(label, cx, cy + labelPaint.textSize * 0.25f, labelPaint)
        captionPaint.textSize = r * 0.085f
        c.drawText(caption.take(28), cx, cy + labelPaint.textSize * 0.25f + captionPaint.textSize * 1.9f, captionPaint)
    }
}

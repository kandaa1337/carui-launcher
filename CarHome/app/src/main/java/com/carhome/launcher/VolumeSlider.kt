package com.carhome.launcher

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * Толстая полоса громкости. Ставится касанием в любую точку и ведётся пальцем —
 * не нужно попадать в маленький кружок, что удобно на ходу.
 * Если полоса выше, чем шире — работает вертикально (заполняется снизу вверх).
 * Рисуется двумя прямоугольниками, перерисовка только когда значение реально меняется.
 */
class VolumeSlider @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var max = 15
        set(v) { field = v.coerceAtLeast(1); invalidate() }

    var value = 0
        private set

    var onUserChange: ((Int) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x24FFFFFF }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFB547.toInt() }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 15f * resources.displayMetrics.scaledDensity
        isFakeBoldText = true
        setShadowLayer(4f * density, 0f, 0f, 0x66000000)
    }

    private val rect = RectF()
    private val clip = Path()
    private var muted = false
    private val vertical get() = height > width

    fun setValue(v: Int) {
        val nv = v.coerceIn(0, max)
        if (nv == value) return
        value = nv
        invalidate()
    }

    fun setMuted(m: Boolean) {
        if (m == muted) return
        muted = m
        invalidate()
    }

    fun setFillColor(color: Int) {
        if (fillPaint.color == color) return
        fillPaint.color = color
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        // Скругление считаем один раз при смене размера, а не в каждом кадре
        val r = minOf(w, h) / 2f
        clip.reset()
        rect.set(0f, 0f, w.toFloat(), h.toFloat())
        clip.addRoundRect(rect, r, r, Path.Direction.CW)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val frac = if (muted) 0f else value.toFloat() / max

        canvas.save()
        canvas.clipPath(clip)
        canvas.drawRect(0f, 0f, w, h, trackPaint)
        if (frac > 0f) {
            if (vertical) canvas.drawRect(0f, h * (1 - frac), w, h, fillPaint)
            else canvas.drawRect(0f, 0f, w * frac, h, fillPaint)
        }
        canvas.restore()

        val label = "${(frac * 100).toInt()}"
        val baseline = -(textPaint.descent() + textPaint.ascent()) / 2
        if (vertical) canvas.drawText(label, w / 2, w * 0.55f + baseline, textPaint)
        else canvas.drawText("$label%", w - h * 0.8f, h / 2 + baseline, textPaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val frac = if (vertical) 1f - e.y / height else e.x / width
                val v = Math.round(frac * max).coerceIn(0, max)
                if (v != value || muted) {
                    value = v
                    muted = false
                    invalidate()
                    onUserChange?.invoke(v) // зовём систему только при реальной смене шага
                }
                return true
            }
        }
        return super.onTouchEvent(e)
    }
}

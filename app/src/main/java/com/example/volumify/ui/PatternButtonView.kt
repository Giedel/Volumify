package com.example.volumify.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View

/**
 * A custom floating button view featuring a modern, minimalist geometric pattern.
 * Strictly contains NO text and NO volume/speaker icons.
 */
class PatternButtonView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#181824")
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val patternPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.5f
        strokeCap = Paint.Cap.ROUND
    }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#38BDF8")
    }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        color = Color.parseColor("#3038BDF8")
    }

    private val arcBounds = RectF()
    private val innerPath = Path()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val shader = LinearGradient(
            0f, 0f, w.toFloat(), h.toFloat(),
            intArrayOf(
                Color.parseColor("#38BDF8"),
                Color.parseColor("#818CF8"),
                Color.parseColor("#C084FC")
            ),
            null,
            Shader.TileMode.CLAMP
        )
        borderPaint.shader = shader
        patternPaint.shader = shader
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cx = width / 2f
        val cy = height / 2f
        val radius = (Math.min(width, height) / 2f) - 6f

        // Outer glow
        canvas.drawCircle(cx, cy, radius + 2f, glowPaint)

        // Glassmorphic background
        canvas.drawCircle(cx, cy, radius, bgPaint)

        // Gradient border
        canvas.drawCircle(cx, cy, radius, borderPaint)

        // --- Minimalist Geometric Pattern ---
        // 1. Concentric geometric arcs
        val arcRadius1 = radius * 0.65f
        arcBounds.set(cx - arcRadius1, cy - arcRadius1, cx + arcRadius1, cy + arcRadius1)
        canvas.drawArc(arcBounds, 45f, 110f, false, patternPaint)
        canvas.drawArc(arcBounds, 225f, 110f, false, patternPaint)

        val arcRadius2 = radius * 0.40f
        arcBounds.set(cx - arcRadius2, cy - arcRadius2, cx + arcRadius2, cy + arcRadius2)
        canvas.drawArc(arcBounds, 135f, 90f, false, patternPaint)
        canvas.drawArc(arcBounds, 315f, 90f, false, patternPaint)

        // 2. Central geometric diamond/node
        innerPath.reset()
        val nodeSize = radius * 0.18f
        innerPath.moveTo(cx, cy - nodeSize)
        innerPath.lineTo(cx + nodeSize, cy)
        innerPath.lineTo(cx, cy + nodeSize)
        innerPath.lineTo(cx - nodeSize, cy)
        innerPath.close()
        canvas.drawPath(innerPath, patternPaint)

        // 3. Four cardinal accent dots
        val dotOffset = radius * 0.78f
        canvas.drawCircle(cx, cy - dotOffset, 2.5f, dotPaint)
        canvas.drawCircle(cx + dotOffset, cy, 2.5f, dotPaint)
        canvas.drawCircle(cx, cy + dotOffset, 2.5f, dotPaint)
        canvas.drawCircle(cx - dotOffset, cy, 2.5f, dotPaint)
    }
}

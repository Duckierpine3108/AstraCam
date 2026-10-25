package com.astracam.dualdevice.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator

class AiScanOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var scanY = 0f
    private var isScanning = false
    private var animator: ValueAnimator? = null

    private val laserPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        strokeWidth = 6f
        style = Paint.Style.STROKE
    }

    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val hudPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8000E5FF")
        strokeWidth = 4f
        style = Paint.Style.STROKE
    }

    fun startScanning() {
        if (isScanning) return
        isScanning = true
        visibility = VISIBLE

        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1800
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                val fraction = it.animatedValue as Float
                scanY = fraction * height
                invalidate()
            }
            start()
        }
    }

    fun stopScanning() {
        isScanning = false
        animator?.cancel()
        animator = null
        visibility = GONE
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!isScanning || height == 0 || width == 0) return

        val w = width.toFloat()
        val h = height.toFloat()

        // 1. Draw Tech Corner Brackets
        val cornerSize = 40f
        val pad = 16f
        // Top-Left
        canvas.drawLine(pad, pad, pad + cornerSize, pad, hudPaint)
        canvas.drawLine(pad, pad, pad, pad + cornerSize, hudPaint)
        // Top-Right
        canvas.drawLine(w - pad - cornerSize, pad, w - pad, pad, hudPaint)
        canvas.drawLine(w - pad, pad, w - pad, pad + cornerSize, hudPaint)
        // Bottom-Left
        canvas.drawLine(pad, h - pad - cornerSize, pad, h - pad, hudPaint)
        canvas.drawLine(pad, h - pad, pad + cornerSize, h - pad, hudPaint)
        // Bottom-Right
        canvas.drawLine(w - pad - cornerSize, h - pad, w - pad, h - pad, hudPaint)
        canvas.drawLine(w - pad, h - pad - cornerSize, w - pad, h - pad, hudPaint)

        // 2. Draw Glow Trail behind laser
        val trailHeight = 120f
        trailPaint.shader = LinearGradient(
            0f, scanY - trailHeight, 0f, scanY,
            Color.TRANSPARENT,
            Color.parseColor("#4400E5FF"),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(pad, (scanY - trailHeight).coerceAtLeast(pad), w - pad, scanY, trailPaint)

        // 3. Draw Laser Line
        canvas.drawLine(pad, scanY, w - pad, scanY, laserPaint)
    }
}
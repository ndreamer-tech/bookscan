package com.bookscan.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

/** 미리보기 위에 인식된 페이지 윤곽과 안내 네모칸을 그린다. */
class OverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var quad: FloatArray? = null
    private var srcWidth = 0
    private var srcHeight = 0
    private var ready = false

    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.argb(120, 255, 255, 255)
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(18f, 14f), 0f)
    }
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 8f
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    fun update(quad: FloatArray?, srcWidth: Int, srcHeight: Int, ready: Boolean) {
        this.quad = quad
        this.srcWidth = srcWidth
        this.srcHeight = srcHeight
        this.ready = ready
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 안내 네모칸 — 이 안을 페이지로 채우면 된다
        val inset = width * 0.06f
        canvas.drawRoundRect(
            inset, inset, width - inset, height - inset, 28f, 28f, guidePaint
        )

        val q = quad ?: return
        if (srcWidth <= 0 || srcHeight <= 0) return

        // 미리보기는 FIT_CENTER — 같은 비율로 줄이고 남는 쪽은 여백
        val scale = minOf(width.toFloat() / srcWidth, height.toFloat() / srcHeight)
        val dx = (width - srcWidth * scale) / 2f
        val dy = (height - srcHeight * scale) / 2f

        val path = Path()
        for (i in 0 until 4) {
            val x = q[i * 2] * scale + dx
            val y = q[i * 2 + 1] * scale + dy
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()

        val main = if (ready) Color.rgb(46, 204, 113) else Color.rgb(243, 156, 18)
        fillPaint.color = if (ready) Color.argb(52, 46, 204, 113) else Color.argb(38, 243, 156, 18)
        edgePaint.color = main
        cornerPaint.color = main

        canvas.drawPath(path, fillPaint)
        canvas.drawPath(path, edgePaint)
        for (i in 0 until 4) {
            canvas.drawCircle(q[i * 2] * scale + dx, q[i * 2 + 1] * scale + dy, 14f, cornerPaint)
        }
    }
}

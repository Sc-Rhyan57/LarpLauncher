package com.larp.launcher.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View

class LarpCanvas(context: Context) : View(context) {

    @Volatile private var ops: IntArray = IntArray(0)
    @Volatile private var texts: List<String> = emptyList()

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()

    fun submit(data: IntArray, joined: String) {
        ops = data
        texts = if (joined.isEmpty()) emptyList() else joined.split('\u0001')
        postInvalidateOnAnimation()
    }

    private fun f(i: Int): Float = java.lang.Float.intBitsToFloat(i)

    private fun style(color: Int, fill: Int, stroke: Float) {
        paint.color = color
        paint.style = if (fill != 0) Paint.Style.FILL else Paint.Style.STROKE
        paint.strokeWidth = if (stroke > 0f) stroke else 1f
    }

    override fun onDraw(canvas: Canvas) {
        val d = ops
        val t = texts
        var i = 0
        while (i < d.size) {
            when (d[i]) {
                OP_LINE -> {
                    if (i + 6 >= d.size) return
                    style(d[i + 5], 0, f(d[i + 6]))
                    canvas.drawLine(f(d[i + 1]), f(d[i + 2]), f(d[i + 3]), f(d[i + 4]), paint)
                    i += 7
                }
                OP_RECT -> {
                    if (i + 7 >= d.size) return
                    style(d[i + 5], d[i + 6], f(d[i + 7]))
                    canvas.drawRect(f(d[i + 1]), f(d[i + 2]), f(d[i + 1]) + f(d[i + 3]), f(d[i + 2]) + f(d[i + 4]), paint)
                    i += 8
                }
                OP_CIRCLE -> {
                    if (i + 6 >= d.size) return
                    style(d[i + 4], d[i + 5], f(d[i + 6]))
                    canvas.drawCircle(f(d[i + 1]), f(d[i + 2]), f(d[i + 3]), paint)
                    i += 7
                }
                OP_TEXT -> {
                    if (i + 7 >= d.size) return
                    val idx = d[i + 5]
                    paint.style = Paint.Style.FILL
                    paint.color = d[i + 4]
                    paint.textSize = f(d[i + 3])
                    paint.typeface = if (d[i + 7] != 0) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                    paint.textAlign = when (d[i + 6]) {
                        1 -> Paint.Align.CENTER
                        2 -> Paint.Align.RIGHT
                        else -> Paint.Align.LEFT
                    }
                    if (idx in t.indices) canvas.drawText(t[idx], f(d[i + 1]), f(d[i + 2]), paint)
                    paint.typeface = Typeface.DEFAULT
                    i += 8
                }
                OP_RRECT -> {
                    if (i + 8 >= d.size) return
                    style(d[i + 6], d[i + 7], f(d[i + 8]))
                    rect.set(f(d[i + 1]), f(d[i + 2]), f(d[i + 1]) + f(d[i + 3]), f(d[i + 2]) + f(d[i + 4]))
                    canvas.drawRoundRect(rect, f(d[i + 5]), f(d[i + 5]), paint)
                    i += 9
                }
                OP_POLY -> {
                    if (i + 1 >= d.size) return
                    val n = d[i + 1]
                    val end = i + 2 + n * 2
                    if (n < 0 || end + 4 > d.size) return
                    path.reset()
                    for (k in 0 until n) {
                        val x = f(d[i + 2 + k * 2])
                        val y = f(d[i + 3 + k * 2])
                        if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    if (d[end + 3] != 0) path.close()
                    style(d[end], d[end + 1], f(d[end + 2]))
                    canvas.drawPath(path, paint)
                    i = end + 4
                }
                OP_ARC -> {
                    if (i + 8 >= d.size) return
                    style(d[i + 6], d[i + 7], f(d[i + 8]))
                    val cx = f(d[i + 1])
                    val cy = f(d[i + 2])
                    val r = f(d[i + 3])
                    rect.set(cx - r, cy - r, cx + r, cy + r)
                    canvas.drawArc(rect, f(d[i + 4]), f(d[i + 5]), d[i + 7] != 0, paint)
                    i += 9
                }
                else -> return
            }
        }
    }

    companion object {
        const val OP_LINE = 1
        const val OP_RECT = 2
        const val OP_CIRCLE = 3
        const val OP_TEXT = 4
        const val OP_RRECT = 5
        const val OP_POLY = 6
        const val OP_ARC = 7
    }
}

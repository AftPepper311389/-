package com.nemoclient.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View

/**
 * 音乐控制按钮：全部自绘矢量图标（24×24 网格按比例缩放），不用 emoji / 文字符号，
 * 在任何字体、任何机型上都是同一个样子，颜色跟随主题。
 */
class MusicIconButton(ctx: Context, icon: Int, private val onClick: () -> Unit) : View(ctx) {
    companion object {
        const val PLAY = 0; const val PAUSE = 1; const val PREV = 2; const val NEXT = 3
        const val STOP = 4; const val LOOP = 5; const val LOOP_ONE = 6; const val SHUFFLE = 7
    }

    var icon = icon
        set(v) { if (field != v) { field = v; invalidate() } }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val r = RectF()

    init {
        background = ripple(null, Ui.f(99f))
        setOnClickListener { onClick() }
    }

    override fun onDraw(c: Canvas) {
        val size = Ui.f(22f)
        val k = size / 24f
        c.save()
        c.translate(width / 2f - size / 2f, height / 2f - size / 2f)
        c.scale(k, k)
        p.color = Palette.ink
        p.strokeCap = Paint.Cap.ROUND; p.strokeJoin = Paint.Join.ROUND
        p.strokeWidth = 2f
        when (icon) {
            PLAY -> { p.style = Paint.Style.FILL; tri(6.5f, 4.5f, 6.5f, 19.5f, 19f, 12f); c.drawPath(path, p); roundOut(c) }
            PAUSE -> {
                p.style = Paint.Style.FILL
                r.set(6f, 4.5f, 10.5f, 19.5f); c.drawRoundRect(r, 1.4f, 1.4f, p)
                r.set(13.5f, 4.5f, 18f, 19.5f); c.drawRoundRect(r, 1.4f, 1.4f, p)
            }
            PREV -> {
                p.style = Paint.Style.FILL
                r.set(5f, 5f, 7.4f, 19f); c.drawRoundRect(r, 1.2f, 1.2f, p)
                tri(19f, 5f, 19f, 19f, 8.8f, 12f); c.drawPath(path, p)
            }
            NEXT -> {
                p.style = Paint.Style.FILL
                r.set(16.6f, 5f, 19f, 19f); c.drawRoundRect(r, 1.2f, 1.2f, p)
                tri(5f, 5f, 5f, 19f, 15.2f, 12f); c.drawPath(path, p)
            }
            STOP -> { p.style = Paint.Style.FILL; r.set(6f, 6f, 18f, 18f); c.drawRoundRect(r, 2.4f, 2.4f, p) }
            LOOP, LOOP_ONE -> {
                p.style = Paint.Style.STROKE
                path.reset()
                path.moveTo(5f, 11f); path.lineTo(5f, 10f); path.quadTo(5f, 7f, 8f, 7f); path.lineTo(18f, 7f)
                path.moveTo(15.2f, 4.2f); path.lineTo(18f, 7f); path.lineTo(15.2f, 9.8f)
                path.moveTo(19f, 13f); path.lineTo(19f, 14f); path.quadTo(19f, 17f, 16f, 17f); path.lineTo(6f, 17f)
                path.moveTo(8.8f, 14.2f); path.lineTo(6f, 17f); path.lineTo(8.8f, 19.8f)
                c.drawPath(path, p)
                if (icon == LOOP_ONE) {
                    path.reset(); path.moveTo(11f, 10.8f); path.lineTo(12.4f, 9.8f); path.lineTo(12.4f, 14.2f)
                    p.strokeWidth = 1.7f; c.drawPath(path, p)
                }
            }
            SHUFFLE -> {
                p.style = Paint.Style.STROKE
                path.reset()
                path.moveTo(4f, 17f); path.lineTo(6.5f, 17f); path.cubicTo(11f, 17f, 12f, 7f, 16.5f, 7f); path.lineTo(19.5f, 7f)
                path.moveTo(17f, 4.4f); path.lineTo(19.6f, 7f); path.lineTo(17f, 9.6f)
                path.moveTo(4f, 7f); path.lineTo(6.5f, 7f); path.cubicTo(8f, 7f, 9.1f, 7.8f, 9.9f, 9.1f)
                path.moveTo(14.1f, 14.9f); path.cubicTo(14.9f, 16.2f, 16f, 17f, 17.5f, 17f); path.lineTo(19.5f, 17f)
                path.moveTo(17f, 14.4f); path.lineTo(19.6f, 17f); path.lineTo(17f, 19.6f)
                c.drawPath(path, p)
            }
        }
        c.restore()
    }

    private fun tri(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
        path.reset(); path.moveTo(x1, y1); path.lineTo(x2, y2); path.lineTo(x3, y3); path.close()
    }

    /** 给三角形的尖角描一圈同色圆头描边，视觉上变成圆角三角 */
    private fun roundOut(c: Canvas) {
        p.style = Paint.Style.STROKE; p.strokeWidth = 2.2f
        c.drawPath(path, p); p.style = Paint.Style.FILL
    }
}

package com.nemoclient.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View

/**
 * PS 风格取色器：左边是饱和度（横）× 明度（纵）方块，右边是一条色相竖条，底下是十六进制和预览色。
 * 拖动时实时回调 onLive；松手回调 onCommit（外面在这里存设置、重建界面）。
 */
class ColorPickerView(ctx: Context, initial: Int, private val onLive: (Int) -> Unit, private val onCommit: (Int) -> Unit) : View(ctx) {
    private val hsv = FloatArray(3)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sv = RectF()
    private val hb = RectF()
    private var drag = 0 // 1 = SV, 2 = 色相

    init { Color.colorToHSV(initial, hsv); if (hsv[1] < 0.02f && hsv[2] < 0.02f) hsv[2] = 0.5f }

    val color: Int get() = Color.HSVToColor(hsv)

    override fun onMeasure(w: Int, h: Int) {
        val ww = MeasureSpec.getSize(w)
        setMeasuredDimension(ww, Ui.u(150f))
    }

    // 三条渐变只和尺寸有关，缓存起来，拖动时每帧不再重新分配
    private var shaderW: Shader? = null
    private var shaderK: Shader? = null
    private var shaderH: Shader? = null

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        shaderW = null; shaderK = null; shaderH = null
        val gap = Ui.f(12f); val barW = Ui.f(22f)
        sv.set(0f, 0f, w - barW - gap, h.toFloat())
        hb.set(w - barW, 0f, w.toFloat(), h.toFloat())
    }

    override fun onDraw(c: Canvas) {
        val r = Ui.f(10f)
        // SV 方块：底色 = 当前色相，叠白色横向渐变，再叠黑色纵向渐变
        p.shader = null; p.style = Paint.Style.FILL
        p.color = Color.HSVToColor(floatArrayOf(hsv[0], 1f, 1f))
        c.drawRoundRect(sv, r, r, p)
        p.shader = shaderW ?: LinearGradient(sv.left, 0f, sv.right, 0f, Color.WHITE, 0x00FFFFFF, Shader.TileMode.CLAMP).also { shaderW = it }
        c.drawRoundRect(sv, r, r, p)
        p.shader = shaderK ?: LinearGradient(0f, sv.top, 0f, sv.bottom, 0x00000000, Color.BLACK, Shader.TileMode.CLAMP).also { shaderK = it }
        c.drawRoundRect(sv, r, r, p)
        p.shader = null
        // 色相条
        p.shader = shaderH ?: LinearGradient(0f, hb.top, 0f, hb.bottom, IntArray(7) { Color.HSVToColor(floatArrayOf(it * 60f, 1f, 1f)) }, null, Shader.TileMode.CLAMP).also { shaderH = it }
        c.drawRoundRect(hb, hb.width() / 2, hb.width() / 2, p)
        p.shader = null
        // 游标
        val cx = sv.left + hsv[1] * sv.width()
        val cy = sv.top + (1f - hsv[2]) * sv.height()
        p.style = Paint.Style.STROKE; p.strokeWidth = Ui.f(2.5f); p.color = Color.WHITE
        c.drawCircle(cx, cy, Ui.f(8f), p)
        p.strokeWidth = Ui.f(1f); p.color = 0x66000000
        c.drawCircle(cx, cy, Ui.f(10f), p)
        val hy = hb.top + hsv[0] / 360f * hb.height()
        p.style = Paint.Style.FILL; p.color = Color.HSVToColor(floatArrayOf(hsv[0], 1f, 1f))
        c.drawCircle(hb.centerX(), hy, hb.width() / 2 + Ui.f(2f), p)
        p.style = Paint.Style.STROKE; p.strokeWidth = Ui.f(2.5f); p.color = Color.WHITE
        c.drawCircle(hb.centerX(), hy, hb.width() / 2 + Ui.f(2f), p)
        p.style = Paint.Style.FILL
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                drag = if (hb.left - Ui.f(8f) <= e.x && e.x <= hb.right + Ui.f(8f)) 2 else 1
                apply(e); return true
            }
            MotionEvent.ACTION_MOVE -> { apply(e); return true }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if (drag != 0) { drag = 0; onCommit(color) }
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    private fun apply(e: MotionEvent) {
        if (drag == 1) {
            hsv[1] = ((e.x - sv.left) / sv.width()).coerceIn(0f, 1f)
            hsv[2] = (1f - (e.y - sv.top) / sv.height()).coerceIn(0f, 1f)
        } else if (drag == 2) {
            hsv[0] = ((e.y - hb.top) / hb.height()).coerceIn(0f, 0.999f) * 360f
        }
        invalidate(); onLive(color)
    }
}

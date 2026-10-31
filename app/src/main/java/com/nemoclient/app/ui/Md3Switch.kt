package com.nemoclient.app.ui

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * Material Design 3 风格开关（无对勾、无光效）：
 * 关 = 描边轨道 + 小滑块；开 = 实心主题色轨道 + 大白滑块；按下时滑块变大。
 */
class Md3Switch(ctx: Context) : View(ctx) {
    var checked = false
        private set
    var onToggle: ((Boolean) -> Unit)? = null

    private var progress = 0f
    private var pressedDown = false
    private var anim: ValueAnimator? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val eval = ArgbEvaluator()

    init { isClickable = true }

    /** 外部同步状态；状态没变时什么都不做，避免打断正在播放的动画 */
    fun setChecked(v: Boolean, animate: Boolean = false) {
        if (v == checked) return
        checked = v
        animateTo(if (v) 1f else 0f, animate)
    }

    private fun animateTo(target: Float, animate: Boolean) {
        anim?.cancel()
        if (!animate) { progress = target; invalidate(); return }
        anim = ValueAnimator.ofFloat(progress, target).apply {
            duration = 200
            interpolator = DecelerateInterpolator()
            addUpdateListener { progress = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(Ui.u(52f), Ui.u(32f))

    private fun mix(a: Int, b: Int) = eval.evaluate(progress, a, b) as Int

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val sw = Ui.f(2f)
        // 轨道
        paint.style = Paint.Style.FILL
        paint.color = mix(withAlpha(Palette.sage, 0.30f), Palette.accent)
        c.drawRoundRect(0f, 0f, w, h, h / 2, h / 2, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = sw
        paint.color = mix(Palette.ink2, Palette.accent)
        c.drawRoundRect(sw / 2, sw / 2, w - sw / 2, h - sw / 2, (h - sw) / 2, (h - sw) / 2, paint)
        // 滑块
        val rOff = Ui.f(8f)
        val rOn = Ui.f(12f)
        var r = rOff + (rOn - rOff) * progress
        if (pressedDown) r = Ui.f(14f)
        val cx = Ui.f(16f) + (w - Ui.f(18f) - Ui.f(16f)) * progress
        paint.style = Paint.Style.FILL
        paint.color = mix(Palette.ink2, Color.WHITE)
        c.drawCircle(cx, h / 2, r, paint)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { pressedDown = true; invalidate(); return true }
            MotionEvent.ACTION_UP -> {
                pressedDown = false
                if (e.x in 0f..width.toFloat() && e.y in 0f..height.toFloat()) {
                    checked = !checked
                    animateTo(if (checked) 1f else 0f, true)
                    onToggle?.invoke(checked)
                    performClick()
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_CANCEL -> { pressedDown = false; invalidate(); return true }
        }
        return super.onTouchEvent(e)
    }

    override fun performClick(): Boolean = super.performClick()
}

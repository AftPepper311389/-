package com.nemoclient.app.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.TextView

/** 按下 / 松开的手感：按下时轻轻缩小并变暗，松开时带一点过冲弹回 */
private fun View.pressFx(down: Boolean, depth: Float = 0.9f) {
    animate().cancel()
    if (down) {
        animate().scaleX(depth).scaleY(depth).alpha(0.82f).setDuration(90).setInterpolator(null).start()
    } else {
        animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(280).setInterpolator(OvershootInterpolator(2.4f)).start()
    }
}

/** 悬浮球：扁平圆形，主题色底，点按唤起 / 收起 GUI，自由拖动不吸边 */
class FloatingBallView(ctx: Context) : TextView(ctx) {
    init {
        text = "N"
        textSize = 16f
        setTextColor(Palette.onAccent)
        gravity = Gravity.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        refreshTheme()
    }

    fun refreshTheme() {
        background = rr(Palette.accent, 999f * resources.displayMetrics.density)
        setTextColor(Palette.onAccent)
    }

    fun pressFx(down: Boolean) = (this as View).pressFx(down, 0.88f)
}

/**
 * 悬浮快捷键：显示模块名，点按切换该模块开关。
 * 紧凑的小胶囊：开 = 主题色，关 = 半透明暗底 + 细描边。按下有缩放 / 变暗反馈，
 * 切换时底色渐变过渡并轻轻“弹”一下，同时给一下很轻的触感。
 */
class ShortcutKeyView(ctx: Context) : TextView(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private val bgShape = GradientDrawable().apply { cornerRadius = 999f * d }
    private var curOn: Boolean? = null
    private var colorAnim: ValueAnimator? = null

    init {
        textSize = 11.5f
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        gravity = Gravity.CENTER
        includeFontPadding = false
        setPadding((11 * d).toInt(), (6 * d).toInt(), (11 * d).toInt(), (6 * d).toInt())
        minHeight = (26 * d).toInt()
        background = bgShape
    }

    private fun paint(on: Boolean, t: Float = 1f) {
        val fromFill = if (on) withAlpha(Palette.inkBlack, 0.62f) else Palette.accent
        val toFill = if (on) Palette.accent else withAlpha(Palette.inkBlack, 0.62f)
        bgShape.setColor(mixColor(fromFill, toFill, t))
        val fromLine = withAlpha(Palette.ink, if (on) 0.18f else 0f)
        val toLine = withAlpha(Palette.ink, if (on) 0f else 0.18f)
        bgShape.setStroke((1 * d).toInt().coerceAtLeast(1), mixColor(fromLine, toLine, t))
        setTextColor(mixColor(if (on) Palette.ink2 else Palette.onAccent, if (on) Palette.onAccent else Palette.ink2, t))
    }

    fun bind(name: String, on: Boolean) {
        text = name
        val prev = curOn
        curOn = on
        colorAnim?.cancel()
        if (prev == null || prev == on) {
            paint(on)
            return
        }
        // 状态变了：底色渐变 + 弹一下
        colorAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 200
            addUpdateListener { paint(on, it.animatedValue as Float) }
            start()
        }
        animate().cancel()
        scaleX = 1.1f; scaleY = 1.1f
        animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(320).setInterpolator(OvershootInterpolator(2.2f)).start()
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    fun pressFx(down: Boolean) = (this as View).pressFx(down, 0.9f)
}

package com.nemoclient.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import com.nemoclient.app.data.BlocksFeed
import com.nemoclient.app.data.SpeedFeed
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 方块计数 HUD（参考 nilore 的 ScaffoldHud）：左边方块图标，中间一条 6 高的圆头进度条（深色轨道 + 主题色填充，弹簧缓动），
 * 右边上面是“N blocks”（粗体），下面是灰色小字的移动速度。进度 = 数量 ÷ 满格（64 / 128 / 256）。
 * 数据来自 BlocksFeed（停止推送 1.5 秒后淡出）和 SpeedFeed；没有真实数据时，“演示数据”开着（或编辑模式）放一组倒数的假数据。
 */
class BlocksHudView(ctx: Context) : HudCanvasView(ctx) {
    private val fade = Tween(0f)
    private val prog = Spring(0f, 250f, 0.7f)
    private var progInit = false
    private val blurBuf = ArrayList<Float>(6)
    private val cube = Path()

    override fun windowW() = dp(2f * PAD + W).toInt()
    override fun windowH() = dp(2f * PAD + H).toInt()

    override fun collectBlur(out: MutableList<Float>, ox: Float, oy: Float) {
        if (blurBuf.size >= 6) {
            out.add(ox + blurBuf[0]); out.add(oy + blurBuf[1]); out.add(blurBuf[2]); out.add(blurBuf[3])
            out.add(blurBuf[4]); out.add(0.30f * cfg.opacity * blurBuf[5])
        }
    }

    private fun drawCube(c: Canvas, cx: Float, cy: Float, s: Float, a: Float) {
        val hw = s * 0.5f
        val q = s * 0.25f
        fill.style = Paint.Style.FILL
        // 顶面
        cube.reset(); cube.moveTo(cx, cy - s * 0.5f); cube.lineTo(cx + hw, cy - q); cube.lineTo(cx, cy); cube.lineTo(cx - hw, cy - q); cube.close()
        fill.color = mulAlpha(withAlpha(Palette.hudText, 0.95f), a); c.drawPath(cube, fill)
        // 左面
        cube.reset(); cube.moveTo(cx - hw, cy - q); cube.lineTo(cx, cy); cube.lineTo(cx, cy + s * 0.5f); cube.lineTo(cx - hw, cy + q); cube.close()
        fill.color = mulAlpha(withAlpha(Palette.hudText, 0.6f), a); c.drawPath(cube, fill)
        // 右面
        cube.reset(); cube.moveTo(cx + hw, cy - q); cube.lineTo(cx, cy); cube.lineTo(cx, cy + s * 0.5f); cube.lineTo(cx + hw, cy + q); cube.close()
        fill.color = mulAlpha(withAlpha(Palette.hudText, 0.38f), a); c.drawPath(cube, fill)
    }

    override fun render(c: Canvas, now: Long) {
        blurBuf.clear()
        val live = BlocksFeed.count >= 0 && now - BlocksFeed.stamp < 1500L
        val demo = !live && (cfg.b("demo") || editing)
        fade.animate(if (live || demo) 1f else 0f, if (live || demo) 200L else 260L, Ease.OUT3, now)
        val f = fade.tick(now).coerceIn(0f, 1f)
        if (f <= 0.01f) return
        needFrame = true

        val count = if (live) BlocksFeed.count else (((now / 400L) % 70L).let { 64 - it.toInt() }).coerceAtLeast(3)
        val full = when (cfg.i("full")) { 1 -> 128f; 2 -> 256f; else -> 64f }
        val target = min(1f, count / full)
        if (!progInit) { prog.set(target); progInit = true }
        prog.target = target
        prog.step(now)
        val frac = prog.value.coerceIn(0f, 1f)

        val l = dp(PAD)
        val top = dp(PAD) + dp(4f) * (1f - f)
        val w = dp(W)
        val h = dp(H)
        val r = h / 2f
        fxBehind(c, l, top, l + w, top + h, r, Palette.accent, f)
        rrect(c, l, top, l + w, top + h, r, mulAlpha(((glassA(118f).toInt()) shl 24) or Palette.bgRgb, f))
        rim(c, l, top, l + w, top + h, r, f)
        if (blurOn) { blurBuf.add(l); blurBuf.add(top); blurBuf.add(w); blurBuf.add(h); blurBuf.add(r); blurBuf.add(f) }

        val cy = top + h / 2f
        val iconS = h - dp(18f)
        drawCube(c, l + dp(14f) + iconS / 2f, cy, iconS, f)

        val low = cfg.b("alert") && count <= BlocksFeed.LOW
        val countText = "$count blocks"
        val showSpeed = cfg.b("speed")
        val bps = if (SpeedFeed.bps >= 0f) SpeedFeed.bps else if (demo) (2.6f + 1.2f * sin(now / 1000f)) else 0f
        val speedText = if (cfg.i("unit") == 1) "%.1fkm/h".format(bps * 3.6f) else "%.2fb/s".format(bps)
        val cw = measure(countText, 13f, true)
        val sw = if (showSpeed) measure(speedText, 9.5f) else 0f
        val tw = max(cw, sw)
        val barL = l + dp(14f) + iconS + dp(10f)
        val barR = l + w - dp(14f) - tw - dp(10f)
        val barH = dp(6f)
        val by = cy - barH / 2f
        rrect(c, barL, by, barR, by + barH, barH / 2f, mulAlpha(withAlpha(Palette.ink, 0.16f), f))
        if (frac > 0.01f) rrect(c, barL, by, barL + (barR - barL) * frac, by + barH, barH / 2f, mulAlpha(if (low) 0xFFE0705F.toInt() else Palette.accent, f))

        val tx = l + w - dp(14f) - tw / 2f
        if (showSpeed) {
            text(c, countText, tx, cy - dp(2f), 13f, mulAlpha(if (low) 0xFFE0705F.toInt() else Palette.hudText, f), bold = true, shadow = false, align = Paint.Align.CENTER)
            text(c, speedText, tx, cy + dp(12f), 9.5f, mulAlpha(Palette.hudText2, f), shadow = false, align = Paint.Align.CENTER)
        } else {
            text(c, countText, tx, centerBaseline(cy, 13f, true), 13f, mulAlpha(if (low) 0xFFE0705F.toInt() else Palette.hudText, f), bold = true, shadow = false, align = Paint.Align.CENTER)
        }
        content.set(l, top, l + w, top + h)
        hasContent = true
    }

    private companion object {
        const val PAD = 16f
        const val W = 232f
        const val H = 40f
    }
}

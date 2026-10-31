package com.nemoclient.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import com.nemoclient.app.data.PosFeed
import kotlin.math.cos
import kotlin.math.sin

/**
 * 坐标 HUD：自己的 X / Y / Z（可选朝向、下界 ÷8 换算）。数据来自 PosFeed（SpeedFeed.pushPosition 也会更新它），
 * 没有真实数据时，“演示数据”开着（或编辑模式）就放一组缓缓移动的假坐标，方便预览排版。
 */
class CoordsHudView(ctx: Context) : HudCanvasView(ctx) {
    private val blurBuf = ArrayList<Float>(6)

    private fun single() = cfg.i("style") == 1
    override fun windowW() = if (single()) dp(2f * PAD + 330f).toInt() else dp(2f * PAD + CARD_W).toInt()
    override fun windowH() = if (single()) dp(2f * PAD + 64f).toInt() else dp(2f * PAD + 10f + 5f * ROW_H + 10f).toInt()

    private val dirsZh = arrayOf("南", "西南", "西", "西北", "北", "东北", "东", "东南")
    private val dirsEn = arrayOf("S", "SW", "W", "NW", "N", "NE", "E", "SE")

    override fun collectBlur(out: MutableList<Float>, ox: Float, oy: Float) {
        if (blurBuf.size >= 6) {
            out.add(ox + blurBuf[0]); out.add(oy + blurBuf[1]); out.add(blurBuf[2]); out.add(blurBuf[3])
            out.add(blurBuf[4]); out.add(0.30f * cfg.opacity * blurBuf[5])
        }
    }

    private fun num(v: Double): String = "%.${cfg.i("decimals")}f".format(v)

    override fun render(c: Canvas, now: Long) {
        blurBuf.clear()
        var x = PosFeed.x; var y = PosFeed.y; var z = PosFeed.z; var yaw = PosFeed.yaw
        if (x.isNaN()) {
            if (!(cfg.b("demo") || editing)) return
            val t = now / 1000.0
            x = 123.4 + 6.0 * sin(t * 0.4); y = 64.0 + 1.5 * sin(t * 0.9); z = -88.2 + 6.0 * cos(t * 0.4)
            yaw = (t * 20.0) % 360.0
        }
        needFrame = true
        lazyMs = 66L
        val showFace = cfg.b("facing") && !yaw.isNaN()
        val nether = cfg.b("nether")
        var faceText = ""
        if (showFace) {
            val yw = ((yaw % 360.0) + 360.0) % 360.0
            val i = (((yw + 22.5) / 45.0).toInt()) % 8
            faceText = dirsEn[i] + " " + dirsZh[i] + "  " + yw.toInt() + "°"
        }
        val l = dp(PAD)
        val top = dp(PAD)
        val r = dp(10f)
        if (single()) {
            val body = "X " + num(x) + "   Y " + num(y) + "   Z " + num(z) + (if (showFace) "   $faceText" else "")
            val w = dp(20f) + measure(body, 12.5f, true)
            val h = dp(28f)
            fxBehind(c, l, top, l + w, top + h, h / 2f, Palette.accent, 1f)
            rrect(c, l, top, l + w, top + h, h / 2f, ((glassA(110f).toInt()) shl 24) or Palette.bgRgb)
            rim(c, l, top, l + w, top + h, h / 2f)
            if (blurOn) { blurBuf.add(l); blurBuf.add(top); blurBuf.add(w); blurBuf.add(h); blurBuf.add(h / 2f); blurBuf.add(1f) }
            text(c, body, l + dp(10f), centerBaseline(top + h / 2f, 12.5f, true), 12.5f, Palette.hudText, bold = true, shadow = false)
            var bottom = top + h
            if (nether) {
                val nt = "NETHER  " + num(x / 8.0) + " / " + num(z / 8.0)
                val nw = dp(20f) + measure(nt, 11f, true)
                val nh = dp(24f)
                val ny = bottom + dp(6f)
                fxBehind(c, l, ny, l + nw, ny + nh, nh / 2f, Palette.accent, 1f)
                rrect(c, l, ny, l + nw, ny + nh, nh / 2f, ((glassA(110f).toInt()) shl 24) or Palette.bgRgb)
                rim(c, l, ny, l + nw, ny + nh, nh / 2f)
                text(c, nt, l + dp(10f), centerBaseline(ny + nh / 2f, 11f, true), 11f, Palette.hudText2, bold = true, shadow = false)
                bottom = ny + nh
            }
            content.set(l, top, l + w, bottom)
            hasContent = true
            return
        }
        val rows = 3 + (if (showFace) 1 else 0) + (if (nether) 1 else 0)
        val w = dp(CARD_W)
        val h = dp(20f + rows * ROW_H)
        fxBehind(c, l, top, l + w, top + h, r, Palette.accent, 1f)
        rrect(c, l, top, l + w, top + h, r, ((glassA(110f).toInt()) shl 24) or Palette.bgRgb)
        rim(c, l, top, l + w, top + h, r)
        if (blurOn) { blurBuf.add(l); blurBuf.add(top); blurBuf.add(w); blurBuf.add(h); blurBuf.add(r); blurBuf.add(1f) }
        var ry = top + dp(10f) + dp(ROW_H) / 2f
        fun row(label: String, value: String, valueColor: Int = Palette.hudText) {
            text(c, label, l + dp(12f), centerBaseline(ry, 9.5f, true), 9.5f, Palette.hudText2, bold = true, shadow = false)
            text(c, value, l + w - dp(12f), centerBaseline(ry, 14f, true), 14f, valueColor, bold = true, shadow = false, align = Paint.Align.RIGHT)
            ry += dp(ROW_H)
        }
        row("X", num(x)); row("Y", num(y)); row("Z", num(z))
        if (showFace) row("FACING", faceText)
        if (nether) row("NETHER", num(x / 8.0) + " / " + num(z / 8.0), Palette.accent)
        content.set(l, top, l + w, top + h)
        hasContent = true
    }

    private companion object {
        const val PAD = 16f
        const val CARD_W = 170f
        const val ROW_H = 22f
    }
}

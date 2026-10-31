package com.nemoclient.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import com.nemoclient.app.data.HudInfo
import com.nemoclient.app.data.SpeedFeed
import com.nemoclient.app.i18n.tr
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 帧率 / 速度计 HUD（v0.17 换样式；v0.17.1 起两个分开，各自独立的 HUD）：一块玻璃小面板，每格左边是大数值 + 小单位标签，
 * 右边是一排竖条柱状图（最近一段时间按 24 段取平均，最新一根用强调色高亮），不再是平滑曲线卡片。
 * 帧率来自 HudInfo；速度来自 SpeedFeed（给它坐标或直接给格/秒）。没有真实速度时，“演示数据”开着就放一条假曲线。
 * 曲线每 250ms 采一个点，窗口长度可选 10 / 20 / 40 秒。
 */
class SpeedHudView(ctx: Context, private val kind: Int = 1) : HudCanvasView(ctx) { // kind 0 = 帧率, 1 = 移动速度（两个独立的 HUD）
    private val hist = Array(2) { FloatArray(MAX_N) }
    private var n = 0
    private var lastSample = 0L
    private val blurBuf = ArrayList<Float>(12)
    private val fade = Tween(0f)

    override fun windowW() = dp(CELL_W + 2f * PAD).toInt()
    override fun windowH() = dp(PANEL_H + 2f * PAD).toInt()

    private fun windowN(): Int = when (cfg.i("window")) { 0 -> 40; 2 -> 160; else -> 80 }

    private fun demoBps(now: Long): Float {
        val t = now / 1000f
        return (2.6f + 2.2f * sin(t * 0.7f) + 0.8f * sin(t * 2.3f)).coerceAtLeast(0f)
    }

    private fun demoFps(now: Long): Float {
        val t = now / 1000f
        return 72f + 8f * sin(t * 0.9f) + 3f * sin(t * 3.1f)
    }

    private fun sample(now: Long, wantFps: Boolean, wantBps: Boolean) {
        if (now - lastSample < SAMPLE_MS) return
        lastSample = now
        val demo = cfg.b("demo") || editing
        val fps = if (HudInfo.fps >= 0) HudInfo.fps.toFloat() else if (demo) demoFps(now) else 0f
        val bps = if (SpeedFeed.bps >= 0f) SpeedFeed.bps else if (demo) demoBps(now) else 0f
        val cap = windowN()
        if (n >= cap) {
            for (k in 0 until 2) System.arraycopy(hist[k], n - cap + 1, hist[k], 0, cap - 1)
            n = cap - 1
        }
        hist[0][n] = fps
        hist[1][n] = bps
        n++
    }

    override fun collectBlur(out: MutableList<Float>, ox: Float, oy: Float) {
        var i = 0
        while (i + 5 < blurBuf.size) {
            out.add(ox + blurBuf[i]); out.add(oy + blurBuf[i + 1]); out.add(blurBuf[i + 2]); out.add(blurBuf[i + 3])
            out.add(blurBuf[i + 4]); out.add(0.30f * cfg.opacity * blurBuf[i + 5])
            i += 6
        }
    }

    override fun render(c: Canvas, now: Long) {
        blurBuf.clear()
        val wantFps = kind == 0
        val wantBps = kind == 1
        sample(now, wantFps, wantBps)
        needFrame = true
        if (!wantFps && !wantBps) return
        val cells = (if (wantFps) 1 else 0) + (if (wantBps) 1 else 0)
        val l = dp(PAD)
        val top = dp(PAD)
        val w = dp(CELL_W) * cells
        val h = dp(PANEL_H)
        val r = dp(12f)
        fxBehind(c, l, top, l + w, top + h, r, Palette.accent, 1f)
        rrect(c, l, top, l + w, top + h, r, ((glassA(110f).toInt()) shl 24) or Palette.bgRgb)
        rim(c, l, top, l + w, top + h, r)
        if (blurOn) { blurBuf.add(l); blurBuf.add(top); blurBuf.add(w); blurBuf.add(h); blurBuf.add(r); blurBuf.add(1f) }
        var x = l
        if (wantFps) { cell(c, 0, "FPS", fmtFps(), x, top); x += dp(CELL_W) }
        if (wantBps) {
            if (wantFps) rrect(c, x - dp(0.5f), top + dp(10f), x + dp(0.5f), top + h - dp(10f), 0f, withAlpha(Palette.ink, 0.12f))
            cell(c, 1, if (cfg.i("unit") == 1) "KM/H" else "BPS", fmtBps(), x, top)
        }
        content.set(l, top, l + w, top + h)
        hasContent = true
    }

    private fun cur(k: Int): Float = if (n > 0) hist[k][n - 1] else 0f
    private fun fmtFps(): String = Math.round(cur(0)).toString()
    private fun fmtBps(): String {
        val v = cur(1)
        return if (cfg.i("unit") == 1) "%.1f".format(v * 3.6f) else "%.2f".format(v)
    }

    private fun cell(c: Canvas, k: Int, label: String, value: String, left: Float, top: Float) {
        val h = dp(PANEL_H)
        val pad = dp(11f)
        // 左：数值 + 标签
        text(c, value, left + pad, top + dp(28f), 18f, Palette.hudText, bold = true, shadow = false)
        text(c, label, left + pad, top + h - dp(10f), 9f, Palette.accent, bold = true, shadow = false)
        if (!cfg.b("graph") || n < 2) return
        // 右：柱状图（把窗口里的点分成 BARS 段取平均）
        val gl = left + dp(CELL_W) - pad - dp(BARS * 4f - 1.5f)
        val gb = top + h - dp(11f)
        val gt = top + dp(12f)
        val cap = windowN()
        val per = cap.toFloat() / BARS
        var mx = 0f
        for (i in 0 until n) mx = max(mx, hist[k][i])
        val scaleMax = max(mx, if (k == 0) 30f else 6f) * 1.1f
        val startIdx = n - cap // 窗口左端对应的下标（可能为负）
        for (b in 0 until BARS) {
            val i0 = (startIdx + b * per).toInt()
            val i1 = (startIdx + (b + 1) * per).toInt().coerceAtLeast(i0 + 1)
            var sum = 0f; var cnt = 0
            for (i in i0 until i1) if (i in 0 until n) { sum += hist[k][i]; cnt++ }
            val bx = gl + dp(b * 4f)
            if (cnt == 0) { // 还没有数据的位置画一个小点
                rrect(c, bx, gb - dp(1.5f), bx + dp(2.5f), gb, dp(1f), withAlpha(Palette.ink, 0.10f)); continue
            }
            val v = (sum / cnt / scaleMax).coerceIn(0f, 1f)
            val bh = max(dp(2f), (gb - gt) * v)
            val newest = b == BARS - 1
            val col = if (newest) Palette.accent else withAlpha(Palette.ink, 0.22f + 0.30f * (b / (BARS - 1f)))
            rrect(c, bx, gb - bh, bx + dp(2.5f), gb, dp(1.2f), col)
        }
    }

    private companion object {
        const val PAD = 16f
        const val CELL_W = 138f
        const val PANEL_H = 52f
        const val BARS = 14
        const val MAX_N = 160
        const val SAMPLE_MS = 250L
    }
}

package com.nemoclient.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.nemoclient.app.data.PotionFeed
import com.nemoclient.app.data.PotionInfo
import com.nemoclient.app.i18n.tr
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 药水效果 HUD（v0.17 重做）。每个效果一张独立的小玻璃条，从左边滑入，到期后向左滑出：
 *  环形：左边一个药水色圆章，外面一圈倒计时环（剩余 ÷ 本次峰值），中间是首字母；
 *        名称（等级 ≥ II 时后面跟一个强调色小徽章），下面一行小字剩余时间；最后 10 秒环和时间变红并轻轻脉动。
 *  条形：更紧凑的一行：药水色竖条 + 名称 + 时间，底边一条细进度线。
 * 数据来自 PotionFeed；“演示数据”开着且没有真实数据时放几条假数据。无限时长显示 **:** 并画满环。
 */
class PotionHudView(ctx: Context) : HudCanvasView(ctx) {
    private class Row(val key: String) {
        var info: PotionInfo? = null
        val prog = Tween(0f)
        var target = false
        var peak = 1
    }

    private val rows = LinkedHashMap<String, Row>()
    private val blurBuf = ArrayList<Float>()
    private val dotP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val arc = RectF()
    private val sorted = ArrayList<PotionInfo>()

    override fun windowW() = dp(190f + 2f * PAD).toInt()
    override fun windowH() = dp(MAX_ROWS * (RING_H + GAP) + 2f * PAD).toInt()

    private fun setVisible(r: Row, v: Boolean, now: Long) {
        if (r.target == v) return
        r.target = v
        if (v) r.prog.animate(1f, 240L, Ease.OUT4, now) else r.prog.animate(0f, 180L, Ease.IN3, now)
    }

    private fun roman(amp: Int): String? = if (amp > 0 && amp < ROMAN.size) ROMAN[amp] else null

    private fun demo(now: Long): List<PotionInfo> {
        val t = (now / 1000L).toInt()
        fun left(total: Int) = total - (t % total)
        return listOf(
            PotionInfo(tr("速度"), 1, left(200), 0x7CAFC6),
            PotionInfo(tr("力量"), 0, left(97), 0x932423),
            PotionInfo(tr("再生"), 1, left(23), 0xCD5CAB),
            PotionInfo(tr("抗火"), 0, left(480), 0xE49A3A)
        )
    }

    private fun fmt(sec: Int): String = if (sec < 0) "**:**" else "%d:%02d".format(sec / 60, sec % 60)

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
        var want = PotionFeed.list.filter { it.seconds != 0 && !(cfg.b("ambient") && it.ambient) }
        if (want.isEmpty() && (cfg.b("demo") || editing)) want = demo(now)
        want = when (cfg.i("sort")) {
            1 -> want.sortedBy { it.name.lowercase() }
            2 -> want.sortedWith(compareByDescending<PotionInfo> { it.amplifier }.thenBy { it.name.lowercase() })
            else -> want.sortedBy { if (it.seconds < 0) Int.MAX_VALUE else it.seconds }
        }.take(MAX_ROWS)

        val keys = HashSet<String>()
        for (w in want) {
            keys.add(w.name)
            val r = rows.getOrPut(w.name) { Row(w.name) }
            r.info = w
            if (w.seconds > r.peak || w.seconds < 0) r.peak = max(r.peak, w.seconds)
            setVisible(r, true, now)
        }
        for (r in rows.values) if (!keys.contains(r.key)) setVisible(r, false, now)
        val iter = rows.values.iterator()
        while (iter.hasNext()) {
            val r = iter.next()
            r.prog.tick(now)
            if (!r.target && r.prog.value <= 0.01f && !r.prog.running) iter.remove()
        }
        if (rows.isEmpty()) return
        needFrame = true

        sorted.clear()
        for (w in want) sorted.add(w)
        for (r in rows.values) if (!r.target) r.info?.let { sorted.add(it) }

        if (cfg.i("style") == 1) renderBars(c, now) else renderRings(c, now)
    }

    private fun low(i: PotionInfo) = cfg.b("warn") && i.seconds in 1..10

    // ---------------- 环形 ----------------
    private fun renderRings(c: Canvas, now: Long) {
        val showTime = cfg.b("duration")
        val showLevel = cfg.b("level")
        val tint = cfg.b("tint")
        val icon = cfg.b("icon")
        val rowW = dp(RING_W)
        val rowH = dp(RING_H)
        var y = dp(PAD)
        var maxX = 0f
        for (i in sorted) {
            val row = rows[i.name]
            val p = (row?.prog?.value ?: 1f).coerceIn(0f, 1f)
            if (p <= 0.01f) continue
            val x = dp(PAD) - dp(34f) * (1f - p)
            val rad = rowH / 2f
            val col = if (tint) (0xFF000000.toInt() or i.color) else Palette.accent
            val warn = low(i)
            val pulse = if (warn) 0.65f + 0.35f * sin(now / 140f) else 1f
            val ringCol = if (warn) 0xFFE0554B.toInt() else col
            fxBehind(c, x, y, x + rowW, y + rowH, rad, ringCol, p)
            rrect(c, x, y, x + rowW, y + rowH, rad, (((glassA(105f) * p).toInt()) shl 24) or Palette.bgRgb)
            rim(c, x, y, x + rowW, y + rowH, rad, p)
            if (blurOn) { blurBuf.add(x); blurBuf.add(y); blurBuf.add(rowW); blurBuf.add(rowH); blurBuf.add(rad); blurBuf.add(p) }

            val cy = y + rowH / 2f
            var tx = x + dp(8f)
            if (icon) {
                val br = dp(13f)
                val cx = x + dp(8f) + br
                // 倒计时环
                ringP.strokeWidth = dp(2.4f)
                arc.set(cx - br + dp(1.2f), cy - br + dp(1.2f), cx + br - dp(1.2f), cy + br - dp(1.2f))
                ringP.color = mulAlpha(withAlpha(Palette.ink, 0.14f), p)
                c.drawArc(arc, 0f, 360f, false, ringP)
                val frac = if (i.seconds < 0) 1f else (i.seconds.toFloat() / (row?.peak ?: i.seconds).coerceAtLeast(1)).coerceIn(0f, 1f)
                ringP.color = mulAlpha(ringCol, p * pulse)
                c.drawArc(arc, -90f, 360f * frac, false, ringP)
                dotP.color = mulAlpha(withAlpha(ringCol, 0.20f), p)
                c.drawCircle(cx, cy, br - dp(4.2f), dotP)
                text(c, i.name.take(1).uppercase(), cx, centerBaseline(cy, 10.5f, true), 10.5f,
                    mulAlpha(if (warn) ringCol else Palette.hudText, p), bold = true, shadow = false, align = Paint.Align.CENTER)
                tx = cx + br + dp(9f)
            } else tx += dp(4f)
            // 名称 + 等级徽章
            val name = i.name
            val ny = if (showTime) cy - dp(5.5f) else cy
            text(c, name, tx, centerBaseline(ny, 12.5f, true), 12.5f, mulAlpha(Palette.hudText, p), bold = true, shadow = false)
            val lv = if (showLevel) roman(i.amplifier) else null
            if (lv != null) {
                val nw = measure(name, 12.5f, true)
                val bw = measure(lv, 9f, true) + dp(8f)
                val bx = tx + nw + dp(6f)
                rrect(c, bx, ny - dp(7f), bx + bw, ny + dp(7f), dp(7f), mulAlpha(withAlpha(ringCol, 0.28f), p))
                text(c, lv, bx + bw / 2f, centerBaseline(ny, 9f, true), 9f, mulAlpha(Palette.hudText, p), bold = true, shadow = false, align = Paint.Align.CENTER)
            }
            if (showTime) {
                text(c, fmt(i.seconds), tx, centerBaseline(cy + dp(8.5f), 10.5f), 10.5f,
                    mulAlpha(if (warn) ringCol else Palette.hudText2, p * (if (warn) pulse else 1f)), shadow = false)
            }
            maxX = max(maxX, x + rowW)
            y += (rowH + dp(GAP)) * p
        }
        content.set(dp(PAD), dp(PAD), max(maxX, dp(PAD) + rowW), max(y - dp(GAP), dp(PAD) + dp(GAP)))
        hasContent = true
    }

    // ---------------- 条形 ----------------
    private fun renderBars(c: Canvas, now: Long) {
        val showTime = cfg.b("duration")
        val showLevel = cfg.b("level")
        val tint = cfg.b("tint")
        val rowW = dp(BAR_W)
        val rowH = dp(BAR_H)
        val rad = dp(8f)
        var y = dp(PAD)
        var maxX = 0f
        for (i in sorted) {
            val row = rows[i.name]
            val p = (row?.prog?.value ?: 1f).coerceIn(0f, 1f)
            if (p <= 0.01f) continue
            val x = dp(PAD) - dp(30f) * (1f - p)
            val col = if (tint) (0xFF000000.toInt() or i.color) else Palette.accent
            val warn = low(i)
            val barCol = if (warn) 0xFFE0554B.toInt() else col
            fxBehind(c, x, y, x + rowW, y + rowH, rad, barCol, p)
            rrect(c, x, y, x + rowW, y + rowH, rad, (((glassA(105f) * p).toInt()) shl 24) or Palette.bgRgb)
            rim(c, x, y, x + rowW, y + rowH, rad, p)
            if (blurOn) { blurBuf.add(x); blurBuf.add(y); blurBuf.add(rowW); blurBuf.add(rowH); blurBuf.add(rad); blurBuf.add(p) }
            c.save()
            clipRound(c, x, y, x + rowW, y + rowH, rad)
            // 底边进度线
            val frac = if (i.seconds < 0) 1f else (i.seconds.toFloat() / (row?.peak ?: i.seconds).coerceAtLeast(1)).coerceIn(0f, 1f)
            rrect(c, x, y + rowH - dp(2.5f), x + rowW, y + rowH, 0f, mulAlpha(withAlpha(Palette.ink, 0.10f), p))
            rrect(c, x, y + rowH - dp(2.5f), x + rowW * frac, y + rowH, 0f, mulAlpha(barCol, p))
            // 左侧药水色竖条
            rrect(c, x + dp(7f), y + dp(6f), x + dp(10f), y + rowH - dp(8f), dp(1.5f), mulAlpha(barCol, p))
            val cy = y + (rowH - dp(2.5f)) / 2f
            val title = i.name + (if (showLevel) roman(i.amplifier)?.let { " $it" } ?: "" else "")
            text(c, title, x + dp(16f), centerBaseline(cy, 12f, true), 12f, mulAlpha(Palette.hudText, p), bold = true, shadow = false)
            if (showTime) text(c, fmt(i.seconds), x + rowW - dp(9f), centerBaseline(cy, 11f), 11f,
                mulAlpha(if (warn) barCol else Palette.hudText2, p), shadow = false, align = Paint.Align.RIGHT)
            c.restore()
            maxX = max(maxX, x + rowW)
            y += (rowH + dp(GAP - 1f)) * p
        }
        content.set(dp(PAD), dp(PAD), max(maxX, dp(PAD) + rowW), max(y - dp(GAP), dp(PAD) + dp(GAP)))
        hasContent = true
    }

    private companion object {
        const val PAD = 16f
        const val RING_W = 168f
        const val RING_H = 40f
        const val BAR_W = 150f
        const val BAR_H = 28f
        const val GAP = 6f
        const val MAX_ROWS = 8
        val ROMAN = arrayOf("", "II", "III", "IV", "V", "VI", "VII", "VIII")
    }
}
